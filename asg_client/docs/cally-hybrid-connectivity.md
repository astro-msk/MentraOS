# Cally hybrid connectivity architecture

Status: analysis and first-POC plan

Audited upstream branch: `dev`

Audited commit: `5c2e9777e3647717c134621288a46bb7caf90d61`
Scope: Mentra Live (`K900`) `asg_client`

## Goal

Mentra Live should be usable as a Cally/Hermes peripheral through two complementary routes:

```text
At home / direct network available

Mentra Live MTK Android
  -> authenticated WSS over Wi-Fi
  -> Cally server / Hermes

Away / direct route unavailable

Mentra Live BES
  -> BLE
  -> iPhone running the Mentra SDK/app path
  -> Mentra Cloud / Cally server / Hermes
```

The iOS SDK remains on the iPhone. It is not installed on the glasses. The direct route is an
additional glasses-side control plane, selected when it is authenticated and healthy; it does not
remove the phone path.

The first POC is intentionally limited to `hello`, `heartbeat`, `ping`, `pong`, and `status`. It does
not route existing camera, microphone, speaker, streaming, OTA, Wi-Fi provisioning, or reboot
commands through WSS. It proves that the direct socket and existing phone/BES path can remain alive
at the same time; direct-preferred command selection and fallback are later production work.

## Decisive architecture finding

`K900BluetoothManager` is not just the phone/BLE transport. Mentra Live has two processors:

- BES is always on and owns BLE/BT Classic, speakers, two microphones, buttons, touch, battery
  signals, the RGB status LED, and parts of OTA/shutdown behavior.
- MTK runs Android and `asg_client`, and owns Wi-Fi, the camera, a third microphone, and heavier
  processing.

MTK and BES communicate over `/dev/ttyS1` UART for control and I2S for audio. On K900,
`K900BluetoothManager` owns that UART as well as the phone-message relay. Replacing it with a WSS
transport, or wrapping it in a generic broadcast composite, would break hardware functions and
could leak raw BES `C/V/B` control packets to the server.

The safe first seam is therefore a lifecycle-owned sidecar:

```text
AsgClientService
  |-- K900BluetoothManager          # unchanged physical BES/UART + phone relay
  `-- DirectWebSocketTransport      # independent Cally control-plane POC
```

`DirectWebSocketTransport` must not implement `ICompanionTransport` in the first POC and must not
feed messages into the existing `CommandProcessor`.

## Relevant source tree

```text
app/src/main/java/com/mentra/asg_client/
  AsgClientApplication.java
  AsgClientBootReceiver.java
  BootstrapActivity.java
  AsgConstants.java

  service/core/
    AsgClientService.java
    ServiceInitializer.java
    processors/
      CommandParser.java
      CommandProcessor.java
      CommandProtocolDetector.java
      ResponseSender.java
    handlers/
      K900CommandHandler.java
      PhotoCommandHandler.java
      VideoCommandHandler.java
      StreamCommandHandler.java
      MicrophoneCommandHandler.java
      PingCommandHandler.java
      subscribers/
        BatteryEventSubscriber.java
        ButtonEventSubscriber.java
        TouchEventSubscriber.java
        SwitchEventSubscriber.java
        SwipeVolumeEventSubscriber.java

  service/communication/
    managers/
      CommunicationManager.java
      ResponseBuilder.java

  service/legacy/
    interfaces/ICommandHandler.java
    managers/AsgClientServiceManager.java

  service/system/managers/
    ServiceLifecycleManager.java

  di/hilt/TransportModule.java

  io/bluetooth/
    core/
      BaseBluetoothManager.java
      BluetoothManagerFactory.java
    interfaces/
      IBluetoothManager.java
      ICompanionTransport.java
    managers/
      K900BluetoothManager.java
      StandardBluetoothManager.java
      mentralive/internal/
        BesMessageParser.java
        BesWireFormat.java
        LinkStateMachine.java
        SerialPortBridge.java

  io/peripheral/
    IPeripheralBus.java
    SimplePeripheralBus.java
    McuEventParser.java
    events/

  io/network/
    interfaces/INetworkManager.java
    managers/K900NetworkManager.java

  io/hardware/
    interfaces/IHardwareManager.java
    managers/K900HardwareManager.java

  camera/
    CameraNeoService.java
    lifecycle/
      CameraCoordinator.java
      PhotoSession.java
      VideoRecordingSession.java
    feedback/
      PhotoFeedbackController.java
      PhotoLightController.java

  io/media/
    core/MediaCaptureService.java
    interfaces/
      AudioChunkCallback.java
      ServiceCallbackInterface.java

  service/media/
    interfaces/IMediaManager.java
    managers/MediaManager.java

  service/system/
    interfaces/IStateManager.java

  io/streaming/
    interfaces/
      IStreamingService.java
      StreamingStatusCallback.java
    services/
      RtmpStreamingService.java
      SrtStreamingService.java
      WhipStreamingService.java
      WhipCameraCapturer.java

  audio/
    I2SAudioController.java
    I2SAudioBroadcastReceiver.java

  hardware/
    K900LedController.java
    K900RgbLedController.java

  io/ota/
    services/OtaService.java
    receivers/MtkOtaReceiver.java

app/src/main/AndroidManifest.xml
app/build.gradle
build.gradle
settings.gradle
gradle/wrapper/gradle-wrapper.properties
```

## Startup and service lifecycle

```text
Android boot / launcher
  -> AsgClientBootReceiver or BootstrapActivity
  -> AsgClientService.onCreate()
  -> foreground notification established
  -> ServiceInitializer constructed
  -> ServiceLifecycleManager.initialize()
  -> AsgClientServiceManager.initialize()
       |-- network manager
       |-- K900BluetoothManager + /dev/ttyS1
       |-- hardware manager
       |-- media manager
       `-- other device services
  -> CommandProcessor and peripheral subscribers active
  -> AsgClientService returns START_STICKY
```

Key ownership points:

- `AsgClientApplication` is the Hilt application and installs process-level integrations.
- `AsgClientBootReceiver` and `BootstrapActivity` start `AsgClientService` after boot, package
  replacement, or launcher activation.
- `AsgClientService` is the existing long-running foreground service and returns `START_STICKY`.
- `ServiceInitializer` creates the command graph and `IPeripheralBus` subscribers.
- `ServiceLifecycleManager` delegates startup and cleanup to `AsgClientServiceManager`.
- `AsgClientServiceManager` initializes and later shuts down network, transport, hardware, media,
  and other device managers.

The direct WSS sidecar should start only after `ServiceLifecycleManager.initialize()` succeeds and
should stop before the existing lifecycle cleanup begins. It does not need a second Android service
or foreground notification.

## Existing transport and command flow

### Phone and BES ingress

```text
iPhone / Mentra app
  <-> BLE
BES
  <-> framed UART on /dev/ttyS1
K900BluetoothManager
  -> BaseBluetoothManager TransportListener callback
AsgClientService.onDataReceived(byte[])
  -> CommandProcessor.processCommand(byte[])
     |-- CommandParser
     |-- CommandProtocolDetector
     |-- ACK + global mId dedupe
     |-- standard JSON -> CommandHandlerRegistry -> ICommandHandler
     `-- BES C command -> K900CommandHandler -> IPeripheralBus
```

Exact responsibility map:

- `TransportModule` provides one `ICompanionTransport`.
- `BluetoothManagerFactory` selects `K900BluetoothManager` for Mentra Live.
- `ICompanionTransport` is an empty alias over the BLE-oriented `IBluetoothManager` interface.
- `K900BluetoothManager` creates `SerialPortBridge`, owns UART framing and message relay, consumes
  internal BES state, and forwards remaining payloads to transport listeners.
- `BesMessageParser` reassembles fragmented `##...$$` frames.
- `BesWireFormat` wraps phone JSON and encodes/decodes the BES wire format.
- `LinkStateMachine` separately tracks phone presence as `UNKNOWN`, `PRESENT`, or `ABSENT`.
- `K900BluetoothManager.isConnected()` means the UART/BES link is available, not that the phone is
  connected. Hybrid selection must not use it as the phone-route health signal.
- `CommandProcessor` parses, acknowledges, deduplicates, detects protocol, and dispatches commands.
- `ICommandHandler` receives only `(commandType, JSONObject)`; it carries no origin or reply route.
- `K900CommandHandler` converts MCU packets into typed events on `IPeripheralBus`.

### Responses and events

The current outbound layer is phone-specific and route-blind:

- `CommunicationManager` owns the one `ICompanionTransport` and sends responses through it.
- `ResponseSender` creates a second reliability manager that also targets that same physical
  transport.
- `ButtonEventSubscriber`, `BatteryEventSubscriber`, touch/switch/swipe subscribers, and media
  managers send directly to the phone transport.
- Deduplication is keyed only by global `mId`, not `(source, mId)`.
- Asynchronous media completion does not remember which transport originated the request.

For these reasons, connecting WSS input directly to `CommandProcessor` in the first POC would create
misrouted acknowledgements, replies sent to the phone, cross-route dedupe collisions, and possible
duplicate user actions.

## Hardware and media abstractions

### Camera and photos

```text
PhotoCommandHandler
  -> MediaCaptureService
  -> CameraNeoService
  -> CameraCoordinator / PhotoSession
  -> Camera2 JPEG
```

Photo capture is highly reusable. `MediaCaptureService.takePhotoAutoTransfer()` already attempts an
OkHttp multipart webhook upload over Wi-Fi and falls back to BLE without recapturing. The missing
piece is source-aware status/result routing: `ServiceCallbackInterface` and the photo lifecycle
emitters are currently Bluetooth-specific.

### Video and streaming

- Local video uses `VideoCommandHandler`, `MediaCaptureService`, `CameraNeoService`, and
  `VideoRecordingSession` (`MediaRecorder`, H.264 video, AAC audio).
- RTMP and SRT services use StreamPackLite with camera and AAC microphone input.
- WHIP uses WebRTC with camera and audio tracks configured as send-only.
- The declared `IStreamingService` abstraction is not implemented by these services; the practical
  shared seam today is `StreamingStatusCallback`.
- Camera ownership is distributed across the photo/video service, StreamPack services, and WHIP.
  A common media resource coordinator is required before both routes can issue media commands.
- WHIP currently can collide with an idle kept-alive Camera2 session because it does not close the
  warm camera the way RTMP/SRT do.

### Microphone input

There is no `AudioRecord` implementation in the current source. `MicrophoneCommandHandler` accepts
mic-state commands but its operations are TODO/no-op, and `AudioChunkCallback` is unused. Current
microphone use is internal to `MediaRecorder`, StreamPackLite, or WebRTC.

The hardware documentation describes BES- and MTK-side microphones, but the generic
`MediaRecorder.AudioSource.MIC` call does not prove which physical microphone Android selects.
Physical input selection and quality require device characterization.

Raw PCM/Opus audio to Cally is therefore a new media subsystem, not a transport-only reuse. It needs
format negotiation, resource ownership, backpressure, lifecycle/error handling, and a bounded wake
policy. BES VAD events can be forwarded as hints but do not contain microphone samples.

### Speaker output

The most reusable first speaker experiment is:

```text
download a complete, device-supported TTS audio file to app-private storage
  -> IHardwareManager.playAudioFile()
  -> K900HardwareManager
  -> I2SAudioController / MediaPlayer
  -> BES I2S start/stop commands over the unchanged UART
  -> speakers
```

This is best-effort asynchronous playback: the existing call returns after starting playback and
does not report completion or failure to the command owner. WAV/PCM is documented; other formats
must be validated on hardware. A reliable command needs explicit completion/error callbacks.

Low-latency streaming TTS later needs an `AudioTrack`/ring-buffer abstraction plus preservation of
the existing BES `mh_starti2s`/`mh_stopi2s`, ODM audio-state, audio-focus, and full-duplex ownership
state machine. The current WHIP implementation is strictly send-only and cannot receive speaker
audio without new signaling and transceiver behavior.

### Buttons, touch, battery, and VAD

`McuEventParser` converts UART messages to typed events. `SimplePeripheralBus` supports multiple
thread-safe subscribers and is the cleanest existing fan-out seam for later direct Cally events.
Existing subscribers should remain in place. A new Cally subscriber can observe events after an
explicit route/arbitration policy is defined.

Blindly mirroring button events and commands to both routes is unsafe: the same press or server
intent could trigger duplicate captures. The production hybrid needs a single active controller
lease/epoch or equivalent idempotency policy.

### LEDs, OTA, and device telemetry

- `K900LedController` owns the local MTK privacy LED.
- `K900RgbLedController` sends RGB commands to BES over UART.
- `K900HardwareManager.setTransport()` explicitly accepts a real `K900BluetoothManager`; a generic
  wrapper would disable RGB/hardware setup.
- BES OTA, battery queries, LED control, I2S control, file transfer, phone readiness, and raw `C/V/B`
  packets must stay on the physical K900 route.
- `IStateManager` provides cached battery/charging state suitable for a nonblocking heartbeat.
- `INetworkManager` exposes Wi-Fi state, but the current broadcast-based manager may miss a network
  that was already connected before listener registration. The direct sidecar must also query the
  active network immediately and use `ConnectivityManager.NetworkCallback`.
- Android's `NET_CAPABILITY_VALIDATED` means Internet validation and must not be a universal gate:
  it can reject a reachable Cally server on a LAN-only access point. Gate on Wi-Fi transport and a
  successful authenticated endpoint session. Validation may be required by policy for public-host
  endpoints or recorded as a diagnostic.

## Smallest architectural seam

### First POC

```text
AsgClientService lifecycle
  -> DirectWebSocketTransport
       |-- validates build-time configuration
       |-- requires Wi-Fi and endpoint reachability
       |-- opens authenticated OkHttp WSS
       |-- hello
       |-- periodic heartbeat/status
       |-- ping -> pong with requestId
       |-- status request -> status with requestId
       `-- capped exponential reconnect
```

The POC remains isolated from `CommandProcessor`, media managers, and the peripheral event bus. The
existing phone route remains initialized and operational at the same time.

### Production hybrid control plane

The minimum safe generalization is separate from the physical transport:

```text
PhysicalPeripheralTransport
  `-- K900BluetoothManager        # unchanged UART/BES ownership

IControlTransport
  |-- PhoneControlTransportAdapter
  `-- DirectWebSocketTransport

ControlPlaneRouter
  |-- source-aware CommandContext
  |-- response-to-origin correlation
  |-- (source, messageId) ACK/dedupe namespaces
  |-- authenticated direct-route health
  |-- direct-preferred / phone-fallback event policy
  `-- one active controller lease/epoch
```

Required later refactors:

1. Add source/session/request identity to command ingress.
2. Route synchronous responses to the command origin.
3. Correlate asynchronous media results to their origin with a TTL.
4. Namespace ACK and dedupe state by source.
5. Introduce an outbound event sink for deliberate preferred/fallback or multicast behavior.
6. Keep phone/BES-only commands on the physical route.
7. Add a safe direct-command allowlist; exclude OTA, downgrade, Wi-Fi provisioning, reboot, raw BES,
   and file-transfer control initially.
8. Add controller election/idempotency before accepting actions from both Cally routes.
9. Add a shared media resource coordinator before enabling direct media commands. It must own
   camera, microphone, speaker/audio focus/I2S, privacy LEDs, wake leases, callbacks, reconnect, and
   shutdown state; current stream callbacks are effectively single-consumer and current graceful
   shutdown does not stop every streaming service.

Route readiness must mean an authenticated, heartbeat-healthy WSS session—not merely association to
an access point. The intended policy is:

```text
authenticated healthy direct WSS -> Cally direct route
otherwise                        -> existing iPhone/Mentra SDK route
```

Responses always return to their origin. Unsolicited peripheral events follow an explicit policy;
they are never accidentally broadcast as commands through both planes.

## First POC protocol

Endpoint:

```text
wss://<cally-host>/v1/glasses/connect
```

Common envelope:

```json
{
  "type": "hello|heartbeat|ping|pong|status",
  "requestId": "optional-correlation-id",
  "timestamp": 0,
  "payload": {}
}
```

The preferred edge authentication is a Cloudflare Access service-token pair. The glasses send
`CF-Access-Client-Id` and `CF-Access-Client-Secret` on each WebSocket upgrade; configuration fails
closed unless both halves are present. `X-Cally-Device-Token` remains a temporary development
fallback for endpoints without Cloudflare Access. It is optional when the Cloudflare pair is
configured, and all three headers are sent when both edge authentication and the legacy
application credential are configured.

No permanent credential may be checked into source. BuildConfig values are extractable from an APK,
so even the Cloudflare pair must be narrowly scoped and revocable; it is not secure per-device
storage. A shared service token authenticates the client at the edge but does not cryptographically
bind the claimed `deviceId`; any holder could impersonate and replace a device session unless Cally
also enforces a per-device identity. Production must add that binding and account for an install ID
changing after app-data loss or reinstall. Production requires `wss://`; release builds must reject
`ws://` despite the application's existing global cleartext allowance. A cleartext endpoint may be
permitted only by an explicit debug build setting.

Hello payload:

```json
{
  "protocolVersion": 1,
  "deviceId": "canonical ro.serialno or generated install id",
  "processSid": "8-hex-process-session-id",
  "deviceModel": "...",
  "androidVersion": "...",
  "androidSdk": 0,
  "appVersion": "..."
}
```

Heartbeat/status payload:

```json
{
  "battery": 82,
  "charging": false,
  "wifiConnected": true,
  "wifiSsid": "...",
  "wifiRssi": -53,
  "localIp": "..."
}
```

The client must:

- send `hello` after every successful socket authentication/open, then wait at most 10 seconds for
  an accepted protocol-version-1 `hello` acknowledgement before declaring the route ready;
- send its initial status/heartbeat and accept server `ping`/status commands only after that
  acknowledgement;
- send a heartbeat periodically and answer server `ping` with `pong` using the same request ID;
- answer `status` with current cached/nonblocking status using the same request ID;
- use OkHttp WebSocket ping plus application heartbeats to detect half-open sessions;
- reconnect after 1, 2, 4, 8, 16, then at most 30 seconds, resetting only after the server accepts
  the application-protocol hello;
- react to network availability/loss/capability transitions;
- reject binary frames and unknown commands, cap message/field/request-ID sizes, and rate-limit
  server ping/status requests;
- add bounded jitter to capped reconnect delays to avoid a fleet reconnect spike;
- send configured Cloudflare Access headers on every WebSocket upgrade and never log service-token
  values, legacy tokens, authorization headers, or credential-bearing endpoint query strings;
- hold no permanent screen, camera, Wi-Fi, or CPU wake lock.

Persistent WSS behavior while MTK sleeps or Android enters doze must be characterized on real
Mentra Live hardware. A later production policy may restrict direct mode by trusted network,
charging state, or battery threshold.

## File-by-file implementation plan

### MentraOS `asg_client`

Add `app/src/main/java/com/mentra/asg_client/io/direct/DirectServerConfig.java`

- Existing: no Cally direct endpoint configuration.
- Proposed: immutable mode, URL, Cloudflare Access pair, optional legacy development token,
  protocol, heartbeat, and validation configuration sourced from `BuildConfig`.
- Dependencies: generated `BuildConfig` fields.
- Risk: accidental secret inclusion; values must come from an ignored `.env` or CI injection.

Add `app/src/main/java/com/mentra/asg_client/io/direct/DirectDeviceStatusProvider.java`

- Existing: device/network facts are spread across `IStateManager`, `INetworkManager`, Android Wi-Fi
  APIs, system properties, and package metadata.
- Proposed: nonblocking hello/status JSON snapshots with canonical serial and generated-install-ID
  fallback.
- Dependencies: Android context, `IStateManager`, `INetworkManager`.
- Risk: charging state is partly inferred; SSID may be unavailable under Android privacy rules.

Add `app/src/main/java/com/mentra/asg_client/io/direct/DirectWebSocketTransport.java`

- Existing: no independent server socket from the glasses.
- Proposed: lifecycle, authenticated OkHttp WSS, protocol parsing, heartbeat, connectivity callback,
  and capped reconnect with jitter. It is deliberately not an `ICompanionTransport`.
- Dependencies: OkHttp 4.9.3 and `org.json`, both already present.
- Risk: MTK sleep/doze, network churn, duplicate client instances, and battery impact.

Add unit tests under `app/src/test/java/com/mentra/asg_client/io/direct/`

- Test config validation, protocol behavior, request-ID echoing, reconnect schedule/reset, lifecycle
  idempotency, malformed messages, and stale-callback rejection.
- A mocked HTTP server dependency may be added only if needed for deterministic WebSocket tests.

Modify `app/src/main/java/com/mentra/asg_client/AsgConstants.java`

- Add named direct-protocol timing and size constants, following repository policy that shared
  constants live here.

Modify `app/src/main/java/com/mentra/asg_client/service/core/ServiceInitializer.java`

- Construct the passive sidecar during graph wiring, then call `start()` in `initialize()` only
  after `lifecycleManager.initialize()` returns.
- Stop the sidecar before existing network/transport cleanup.
- Use a closed/generation guard; unregister network callbacks, cancel heartbeats/reconnects, and
  reject stale OkHttp callbacks so cleanup cannot resurrect a socket.
- Leave `ICompanionTransport` and `K900BluetoothManager` untouched.

Modify `app/build.gradle` and `.env.example`

- Add build-time mode, URL, Cloudflare Access service-token pair, optional legacy development token,
  and explicit debug-cleartext settings.
- Do not add a second OkHttp dependency.
- No Android manifest permission change is required.

Modify `docs/mentra-live-spec.md` and product/architecture documentation

- Record the dual-route product behavior and the physical UART/control-plane distinction.

### Cally server (`cally-mentra`)

Add `src/direct-glasses.ts`

- Attach an authenticated `/v1/glasses/connect` WebSocket endpoint to the existing Express server.
- Validate envelope size/schema, require `hello` before other messages, keep one current connection
  per device/session, track heartbeat freshness, and expose server-side ping/status requests.

Modify `src/index.ts`

- Register the direct gateway without changing the existing `@mentra/sdk` session path.
- Add authenticated diagnostic/control routes to list direct devices and request ping/status.
- Close all WSS resources during app shutdown.

Modify `package.json`, lockfile, `.env.example`, and `README.md`

- Add the WebSocket server dependency/types and document direct-device variables and test commands.

Add tests

- Verify rejected authentication, hello registration, duplicate-device replacement, ping/pong and
  status correlation, malformed/oversized messages, heartbeat timeout, and shutdown cleanup.

## Build and deployment baseline

Current build metadata:

- Gradle wrapper: 8.7
- Android Gradle Plugin: 8.5.2
- Required JDK: 17
- Java source/target: 17
- compile SDK: 34
- target SDK: 33
- minimum SDK: 28
- namespace/application ID: `com.mentra.asg_client`
- build types: debug and release; no product flavors
- local builds without Mentra's signing credentials receive `.thirdparty`, producing
  `com.mentra.asg_client.thirdparty`
- production signing requires Mentra's keystore and credentials
- expected debug output: `app/build/outputs/apk/debug/app-debug.apk`

Local verification completed on 2026-08-29 after installing JDK 17/Android SDK 34, initializing the
expected `StreamPackLite` revision, and creating a disposable Android debug keystore:

- `:app:compileDebugJavaWithJavac` passed;
- all 23 direct package debug unit tests passed;
- `:app:assembleDebug` passed;
- output: `app/build/outputs/apk/debug/app-debug.apk` (109 MB);
- application ID: `com.mentra.asg_client.thirdparty`;
- SHA-256 for that local artifact:
  `f607220f07d39e78e48fb5416da7ef76496da52a7cbc19dff52c9af86831a25e`.

That artifact was intentionally built without a local `.env`, so direct mode is disabled inside it.
It proves that the source packages successfully, not that a glasses-to-server connection has run.
Rebuild with the direct settings for the device smoke test. No Mentra Live was attached to this
environment, so APK installation, boot behavior, and physical ping/pong remain pending.

The Cally side also passed TypeScript checking, all 9 gateway integration tests, and a smoke test
through the real `CallyMentraApp` listener. An ASG-shaped local client completed
HTTP ping → WebSocket ping → correlated pong → HTTP 200 in 3 ms. That validates server integration,
not wearable/network latency.

The local third-party package avoids signature collision, but the development setup script disables
the stock ASG while the fork runs. “Alongside” refers to preserving the phone/BES control path inside
the modified fork, not running two independent ASG processes against the same hardware managers.

## Permissions and security constraints

The current manifest already includes `INTERNET`, Wi-Fi/network state, boot, wake-lock, and
foreground-service permissions. `AsgClientService` already runs as a camera/microphone foreground
service. The WSS POC needs no new permission or exported Android component.

The production ASG is a release-signed system app. A separately signed POC can validate networking
and lifecycle, but privileged/vendor hardware behaviors cannot be assumed until exercised through
the repository's supported third-party deployment flow.

The current application permits cleartext traffic and exposes several legacy components. The new
control plane must not add an unauthenticated Intent/service surface. It must use TLS, explicit device
authentication, an allowlisted protocol, bounded payloads, sanitized logs, and eventually per-device
short-lived credentials or challenge-response/mTLS.

## First-PR acceptance criteria

The code-level POC is complete when:

1. The existing phone/BES transport and hardware graph are unchanged.
2. The Cally server accepts an authenticated glasses WSS connection.
3. The client sends `hello`, heartbeat, and status.
4. Server `ping` receives a correlated `pong`.
5. Server `status` receives a correlated status response.
6. Client start/stop is idempotent and cleanup closes callbacks, schedules, socket, and client.
7. Network/server interruption produces capped exponential reconnect.
8. Server and pure unit/integration tests pass.
9. Android source passes formatting/static review.
10. No raw UART/BES packet can appear on WSS.

These criteria establish code-level POC completion only. Operational success still requires a real
Mentra Live smoke test covering APK install, boot, screen-off/doze, Wi-Fi loss and roaming, a LAN-only
server, public Internet WSS, server restart, simultaneous phone pairing, buttons, cached battery,
BES LED/I2S behavior, and graceful shutdown. Hardware checks may be recorded as pending when no
device is available, but must not be reported as passed.

Camera, microphone, speaker, video, and general command routing are intentionally excluded from this
first PR.

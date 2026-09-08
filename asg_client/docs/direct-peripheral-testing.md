# Cally direct glasses peripheral

The ASG client can maintain an authenticated WebSocket connection to a Cally gateway over Wi-Fi.
The phone/BES connection remains available, while Cally can request bounded camera, microphone,
speaker, LED, button-status, and device-status operations through the direct connection.

Build configuration is read from `asg_client/.env` or process environment variables:

```dotenv
CALLY_DIRECT_MODE=enabled
CALLY_DIRECT_URL=wss://glasses.example.com/v1/glasses/connect
CALLY_DIRECT_DEVICE_TOKEN=
CALLY_DIRECT_CF_ACCESS_CLIENT_ID=
CALLY_DIRECT_CF_ACCESS_CLIENT_SECRET=
CALLY_DIRECT_ALLOW_CLEARTEXT_DEBUG=false
```

Use either a device bearer token or both Cloudflare Access service-token fields. The `.env` file is
gitignored. Never commit real tokens; the configured values become BuildConfig constants in the APK.
Production endpoints must use `wss://`. Cleartext `ws://` is limited to explicitly opted-in debug
builds.

The authenticated protocol provides:

| Capability | Behavior |
| --- | --- |
| Status and heartbeat | Device identity, battery, network, process session, and connection health |
| Ping | WebSocket round-trip test |
| Camera | Privacy-LED capture at the sensor `high` profile, currently 1920×1440 JPEG at quality 85, with the original JPEG sent without recompression and a 1 MiB media limit |
| Voice | A bounded 16 kHz mono PCM recording with energy-based endpoint detection |
| Microphone diagnostic | A fixed two-second 16 kHz mono PCM recording |
| Speaker | Ordered 24 kHz mono PCM streaming with sequence checks, backpressure, and about 43 seconds of bounded buffering |
| LED | Two short green RGB pulses |
| Buttons | Process-local camera-button count, last press type, and timestamp; existing phone/gallery behavior is preserved |

Camera and microphone operations reject overlap, run outside the WebSocket callback, and release
their resources on completion or failure. Camera files live in the app cache and are deleted after
encoding. Active video streaming blocks diagnostic media capture. Server responses containing media
must use `Cache-Control: no-store`.

For normal Cally voice interaction, Cally transcribes the uploaded PCM and submits the transcript to
the Hermes message handler. The response pipeline may begin TTS at completed phrase boundaries while
the model continues generating. The gateway converts speech to 24 kHz PCM, sends `speech_begin`,
ordered `speech_chunk` messages, and `speech_end`; the glasses play those samples through the I2S
speaker path. Discord history and agent/model selection belong to the Cally gateway and are not part
of the APK.

Physical capture remains the user's consent signal. Keep the camera privacy LED enabled and do not
add passive camera or microphone activation. The direct layer is deliberately organized as a hardware
adapter so future glasses can implement the same Cally operations without copying Mentra-specific
gateway logic.

## Hardware verification — 2026-09-07

- Authenticated direct WebSocket, heartbeat, and USB-free Wi-Fi ping passed.
- Wi-Fi off/on recovery reconnected automatically and the follow-up ping passed.
- Camera returned a decodable high-profile JPEG through Cally.
- Microphone PCM, speaker click, RGB LED, and physical camera-button reporting passed.
- A 6.6-second spoken response delivered 316,800 PCM bytes in 39 ordered chunks and played fully.
- Focused Android direct-transport tests and the ASG Java compile passed on the fork's `main` branch.

USB is required for APK installation and local ADB diagnostics. It is not in the runtime path between
the glasses and Cally.

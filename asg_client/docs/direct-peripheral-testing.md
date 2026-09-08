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
| Camera | Privacy-LED capture at the sensor `high` profile (resolution depends on supported sensor outputs), original JPEG without recompression, 1 MiB limit, and audible shutter after capture |
| Short video | Five seconds at the 720p profile, H.264/AAC MP4, visible indicator, bounded camera waits, and 8 MiB limit |
| Voice | A bounded 16 kHz mono PCM recording with energy-based endpoint detection |
| Microphone diagnostic | A fixed two-second 16 kHz mono PCM recording |
| Speaker | Ordered 24 kHz mono PCM streaming with sequence checks, backpressure, and about 43 seconds of bounded buffering |
| LED | Two short green RGB pulses |
| Buttons | Direct builds reserve right short press for photo and right long press for voice. Stock builds retain phone/gallery behavior. The power button remains available for its original function. |

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

## Conversation and playback update

PCM completion now waits for the final playback-head frame before releasing AudioTrack and I2S.
Zero-byte writes during speaker startup are retried with a bounded stall timeout. Short replies are
primed with silence, and a short silent tail keeps the route open beyond the final audible sample.
The `speech_end` response acknowledges drained playback, rather than accepting queued data. The
gateway waits for this acknowledgement in an owned background task so Hermes' ten-second synthesis
finalization timeout cannot cut off a longer queued reply. Completion clicks are suppressed during
speech. Whole-file Hermes TTS and text/photo replies have a PCM fallback delivery path.

Cally's configured toolsets now apply to the glasses, including Spotify, terminal, memory, skills,
and scheduling. Only workflow-preview tools remain sandbox drafts. A voice request can invoke
`cally_capture` for a fresh photo or five-second video. The gateway returns an immutable media path
and queues it for the configured Discord history channel.

After a physical voice start, `followup_enabled` permits another audible listening cue after each
completed spoken reply. The session stops on silence or an end-conversation command, with a maximum
of ten follow-up turns or five minutes. It is turn-based listening, not simultaneous open-mic
barge-in. The same `glasses` conversation identifier retains context across those turns.

Additional controls are exposed through `cally_controls`:

- Repeat the last answer.
- Stop the current speaker stream (also available from Discord).
- Read live battery and Wi-Fi status.
- Remember a brief or detailed response preference.
- End follow-up listening.

The camera tool plus the existing vision tools supports "read this aloud", "translate this", and
"what am I looking at". Discord free-response configuration enables text chat in `glasses-cally`.
Discord and glasses use their normal Hermes sessions; this does not merge their full transcripts.

Device regression evidence: 0.792-second and 9.744-second PCM replies both reached their final
playback frames; the longer reply contained 58 chunks. An MP4 capture decoded with video and audio
tracks and a duration of 4.971 seconds. Automated follow-up tests verify that listening is not armed
before playback acknowledgement. Human conversation and perceived audio quality still require use
of the device; a successful frame drain is not an acoustic measurement.

Custom-build bootstrap wakes and resumes its activity before starting the microphone foreground
service. Package-replacement startup was verified with Android reporting
`allowWhileInUsePermissionInFgs=true`; a subsequent test while Android slept opened the microphone
and ended normally with `no_speech_detected` after 4.32 seconds.

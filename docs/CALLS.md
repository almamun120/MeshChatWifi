# Calls (WebRTC) and File Share (ShareIt-style)

No internet, no server. BLE = signalling only. Media and files = Wi-Fi Direct.

Flow (both features): INVITE → RINGING → LINK (Wi-Fi Direct SSID/passphrase, encrypted over BLE) → ACCEPT → TCP `SecureStream`
(AES-GCM, key = HKDF(ECDH, sessionId)). Only one Wi-Fi session at a time (`LinkGate`); others get BUSY.

* Audio/video call: WebRTC (`stream-webrtc-android`), no STUN/TURN; SDP+ICE go over the SecureStream. Video: camera on/off, flip camera, speaker, mute, remote-camera-off indicator.
* File share: Share tab → Send (Photos/Videos/Music via MediaStore, Files via system picker) → choose person → they Accept → plain TCP,
  128 KiB chunks, SHA-256 per file, 5 GHz preferred. Received files: Downloads/MeshChat.
* Text / voice / image / location messages are unchanged.

Code: `core/` (pure Kotlin, JVM-tested: CallManager, TransferManager, SecureStream, protocol) · `call/` (WifiDirectGroup, WebRtcLink, VideoHub, CallAlerts) · `share/` · `ui/CallScreen.kt`, `ui/ShareScreens.kt`.

Known limits: never built or run on a device by the author's tooling (no Android SDK there); verify the WebRTC artifact version on Maven Central;
APK grows ~10–15 MB per ABI; Wi-Fi Direct behaves differently per vendor; Android 8–9 may show a system invite prompt; calls work best when phones are 1 hop apart (media does not relay over the mesh);
background calls rely on the foreground service's microphone/camera types; missed calls/received files notify, but call history lines in chats are not stored.

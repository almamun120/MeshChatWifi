# MeshChat — release shrinking is disabled by default (isMinifyEnabled = false).
# If you enable it, Room/Compose consumer rules are bundled with the libraries.

# WebRTC (only matters if minification is turned on)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

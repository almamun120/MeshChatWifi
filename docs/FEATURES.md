# MeshChat v1.1 features — design and limits

## Identity backup
`IdentityBackup` writes one JSON file: `{app, version, nodeId, kdf, iterations, salt, iv, data}`. `data` is AES-256-GCM of
`{priv, pub, name, dob, showDob}`; the key comes from PBKDF2-HMAC-SHA256 (210 000 iterations) of the passphrase; the Node ID is
the GCM associated data. Restore is only offered while no profile exists (first launch). Lost passphrase = lost backup.
Old chat history is NOT in the backup (only identity + profile). The restored key is put in the Android Keystore wrapper like a new one.

## Chat features
Wire format: `ContentCodec` kind byte bit 0x80 = envelope; flags 1 ttl, 2 reply, 4 group, 8 push-to-talk. Plain text keeps the old format.
- Reply: sender includes the replied message id + a ≤120-byte quote. Ids are equal on both phones (1:1: packet id; group: logical id).
- Delete for everyone: control message DELETE(target id); the receiver deletes only if the DELETE sender wrote that message. Peers on old builds ignore it (message stays there).
- Disappearing: per-chat timer for what *I* send; each phone deletes at send/receive time + timer. The other side's timer is theirs. A deleted-for-time message can still be screenshotted/copied.
- Search: SQL LIKE over text messages (voice/images are not searched). Pin/mute/timer are local only.

## Private groups
Membership by GROUP_INVITE (creator -> members, auto-accepted unless the creator is blocked), GROUP_LEAVE. Max 8 members.
A group message is N separate pairwise-encrypted packets (cost grows with members; media = N blobs). Delivery ticks in groups mean "handed to the mesh", not "everyone received".
Only the creator can change the group: rename, add and remove members, delete (a GROUP_INVITE carrying the new name+member list goes to old and new members; a person missing from it was removed; the creator sending GROUP_LEAVE deletes the group everywhere). Members can leave. Messages from non-members are ignored. Location sharing is disabled in groups.

## SOS
`PacketType.SOS(11)`: signed, broadcast, TTL 8, not stored. Payload: state, battery, optional location, name. Sender repeats every 45-60 s (new packet each time) until "I'm safe" (sent 3x).
Receivers: rate limit 1 per sender per 10 s (state change always passes), loud notification channel, SOS list, banner. Switch off in Settings = no alert/list, still relays.
Unknown sender key -> shown as "unverified". Not a replacement for emergency services; range limited to the mesh. Older app versions drop SOS packets (unknown type) and do not relay them.

## Push-to-talk
(A) Clip: voice message with the PTT flag, <=15 s, over the BLE mesh (chat or group). Receiver can switch PTT off (clip is acknowledged but dropped) and can disable auto-play.
(B) Live: `SessionKind.PTT` = audio call over Wi-Fi Direct with both microphones muted until the button is held (half-duplex by habit, not enforced). Receiver off => declined silently; optional auto-connect without ringing.
Live PTT needs the same Wi-Fi Direct set-up time (a few seconds) as a call; there is no multi-hop live audio.

## Profile e-mail (optional)
Set at onboarding or in Me. It is appended to the signed IDENTITY payload (`emailLen(1) email`, <= 64 bytes) and shown in Nearby and in group member lists.
Without an e-mail the payload is byte-identical to the old format, so older builds still accept the identity; identities WITH an e-mail are dropped by older builds.

## Nearby tab
Sub-tabs Active / Inactive. Active is sorted nearest->farthest or the reverse. Distance = estimate: 1 hop uses RSSI (path loss, -59 dBm at 1 m, exponent 2.5, clamped 0.5-200 m); relayed nodes use hops x 20 m. Inactive people (known key, no route) can be deleted from the list; they reappear when heard again.

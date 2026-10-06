# MeshChat — Architecture ও Protocol Design (v1)

MeshChat একটি Internet-free, server-free, BLE-ভিত্তিক community chat। প্রতিটি ফোন একই সঙ্গে
Scanner + Advertiser + GATT Client + GATT Server + Relay Node।

## 1. Layer diagram

```
UI (Compose, Material 3)
   ↓  StateFlow
MainViewModel
   ↓
MeshRepository ──── Room (MeshDatabase / RoomMeshStore)
   ↓
MeshEngine  (routing, dedup, relay, ACK/retry, store-and-forward, sync, crypto)
   ↓  MeshTransport interface  (core প্যাকেজে কোনো Android import নেই → JVM unit test সম্ভব)
BleTransport (Advertiser, Scanner, GATT Server, GATT Client, Fragmentation)
   ↓
Android Bluetooth APIs
```

`MeshService` (Foreground Service, type `connectedDevice`) প্রক্রিয়াটিকে জীবিত রাখে এবং `MeshRepository.startMesh()` চালায়।

## 2. Identity ও Node ID

* প্রথম run-এ ECDSA/ECDH **P-256** key pair তৈরি হয়।
* **Node ID = SHA-256(public key X.509 bytes) এর প্রথম 4 byte** → 8 hex অক্ষর, UI-তে `MC-7F3A92B1`।
  MAC, IP বা GPS কোনোটির ওপর নির্ভর করে না। (উদাহরণের ৬ hex-এর বদলে ৮ hex নেওয়া হয়েছে: ৩-byte ID-তে
  ১০০০ জনের community-তে ~১.৫% collision সম্ভাবনা থাকে; ৪-byte-এ তা ~০.০১%।)
* Private key Android Keystore-এর AES-GCM key দিয়ে encrypt করে app-private storage-এ থাকে।
* `allowBackup=false` + `dataExtractionRules` → backup/restore-এ key যায় না।
* **Reinstall করলে** নতুন key → **নতুন Node ID**। এটি ইচ্ছাকৃত: private key বের করার কোনো পথ নেই, আর
  পুরোনো ID অন্য কেউ ব্যবহার করতে পারে না। ট্রেড-অফ: পুরোনো chat history/পরিচিতি হারায়।
* DOB কখনো mesh packet বা advertisement-এ যায় না; শুধু ফোনের Room database-এ থাকে।
  "Show DOB" সুইচ কেবল Profile স্ক্রিনে দেখানো/লুকানো নিয়ন্ত্রণ করে।
* GPS কখনো পড়া হয় না, পাঠানো হয় না। Location permission শুধু Android ≤ 11-এ BLE scan-এর জন্য।

## 3. Advertisement (≤ 31 byte)

```
Flags (সিস্টেম)         3 byte
Service UUID (128-bit)  18 byte   4d455348-4348-4154-8000-000000000001
Manufacturer data       10 byte   company=0xFFFF | proto(1) | caps(1) | nodeId(4)
```
কোনো নাম, DOB, লোকেশন নেই। 31 byte-এ না ধরলে manufacturer data scan-response-এ চলে যায় (fallback আছে)।

Capability flags: `CHAT=1, RELAY=2, STORE=4`।

## 4. GATT Service

Service `…0001`:

| Characteristic | UUID suffix | Property | কাজ |
|---|---|---|---|
| Node Info | `…0002` | READ | proto, caps, nodeId (link যাচাই) |
| Message RX | `…0003` | WRITE | peer → আমি: chat/ack/identity packet |
| Message TX | `…0004` | NOTIFY | আমি → peer: chat/ack/identity packet |
| Sync | `…0005` | WRITE + NOTIFY | inventory/want frame |
| Control | `…0006` | WRITE + NOTIFY | HELLO (route advertisement) |

**Link model:** যে node-এর ID ছোট (string compare) সে client হয়ে connect করে; বড় ID-র node ১২ সেকেন্ড অপেক্ষা
করে তারপর নিজে connect করতে পারে। দুই দিকে link হয়ে গেলে নির্ধারিত নিয়মে (lower-ID-initiated টি থাকে)
একটি বন্ধ হয়। একটি link-ই দ্বিমুখী: client → peer-এর RX/SYNC/CONTROL-এ write, peer → client-এ notify।
সর্বোচ্চ ৫টি একযোগে link (Android-এর বাস্তব সীমার মধ্যে)।

### Fragmentation

প্রতিটি ATT frame: `fragId(1) | index(1) | total(1) | chunk`। Chunk = (MTU−3)−3। MTU 247 অনুরোধ করা হয়;
না পেলে 23 (chunk = 17 byte)। Packet সর্বোচ্চ 2048 byte। এক link-এ packet-গুলো mutex দিয়ে serialize হয়,
তাই reassembly কেবল ক্রমানুসারে চলে; timeout 10s।

## 5. Packet format (big-endian)

```
ver(1)=1 | type(1) | flags(1) | ttl(1) | hops(1) | msgId(8) | src(4) | dst(4) | timestamp(8) | len(2) | payload | [sigLen(1) sig]
```
`dst = FFFFFFFF` মানে broadcast। Dedup key = `src + msgId`। Signature ttl/hops বাদে বাকি immutable field ঢেকে রাখে।

| Type | নাম | Scope | Payload |
|---|---|---|---|
| 1 | HELLO | এক-hop (relay হয় না) | name, caps, route list `(nodeId, hops)` |
| 2 | IDENTITY | flood | caps, name, public key (self-certifying, signed) |
| 3 | ANNOUNCE | flood | author name + content (signed) |
| 4 | PRIVATE | routed unicast | nonce(12) + AES-256-GCM ciphertext |
| 5 | ACK | routed unicast | acked msgId |
| 6 | SYNC_INV | এক-hop | (src, msgId) তালিকা |
| 7 | SYNC_WANT | এক-hop | (src, msgId) তালিকা |

## 6. Routing

* **Neighbor table:** advertisement থেকে `nodeId, RSSI, lastSeen, linked`; ২০ সেকেন্ডে না দেখা গেলে (এবং link না থাকলে) expire।
* **Distance-vector:** প্রতি link-এ প্রতি ৫/১০/২০ সেকেন্ডে (HIGH/NORMAL/LOW) HELLO। Receiver `hops+1` দিয়ে route শেখে।
  Split-horizon (যে peer থেকে route শেখা, তাকে সেটি ফেরত বলা হয় না), MAX_HOPS=8 (count-to-infinity সীমিত),
  route ৭৫ সেকেন্ডে expire, link ভাঙলে ওই next-hop-এর সব route সঙ্গে সঙ্গে মোছা।
* **Forwarding:** `ttl ≤ 1` হলে drop; নইলে `ttl−1, hops+1`। Broadcast → উৎস ও আগমন-link বাদে সব link-এ। Unicast → route-এর next hop।
* **Loop/storm প্রতিরোধ:** duplicate cache (capacity 4000, ১০ মিনিট), TTL, আগমন-link-এ ফেরত না পাঠানো।

## 7. Reliability ও Store-and-Forward

* প্রতিটি hop-এ GATT write-with-response (link-level ack)।
* End-to-end: PRIVATE-এর জন্য ACK। Sender backoff `8s,16s,32s,64s,120s`, সর্বোচ্চ ৫ চেষ্টা, তারপর `FAILED`।
  Duplicate PRIVATE এলে receiver আবার ACK দেয় (ACK হারালে অসীম retry হয় না)।
* **Pending table (Room):** route না থাকলে packet এখানে জমে (relay + originated), সর্বোচ্চ ২০০টি, ২৪ ঘণ্টা expiry।
  Route তৈরি হলে (link-up/HELLO) retry loop (৩ সেকেন্ড) পাঠিয়ে দেয়।

## 8. Message sync (Announce)

Link তৈরি হলে এবং পর্যায়ক্রমে (৬০/১২০/১৮০ সেকেন্ড) সাম্প্রতিক ৬০টি post-এর `(src,msgId)` তালিকা (INV) পাঠানো হয়।
Receiver যেগুলো নেই সেগুলো WANT-এ চায়; sender শুধু সেই post-গুলোর মূল (signed) packet `ttl=1`-এ পাঠায়।
পুরো history বারবার যায় না। (সীমা: ৬০টির পুরোনো post sync হয় না।)

## 9. Security

* Private chat: static-static ECDH(P-256) → HKDF-SHA256 → AES-256-GCM; AAD = src‖dst‖msgId। Relay শুধু header দেখে, plaintext নয়।
  **Forward secrecy নেই** (static key) — future work: ephemeral/ratchet।
* Announce: ECDSA signature। Identity জানা থাকলে verify; না থাকলে "unverified" চিহ্ন।
* Identity key pinning (TOFU): একই Node ID-তে ভিন্ন key এলে উপেক্ষা।
* Spam: প্রতি author-এ token bucket (৫ post, ১২ সেকেন্ডে ১টি refill), ২৮০ অক্ষর সীমা, unsigned post বাতিল।
* Block/mute: `NodeEntity.blocked/muted`; blocked node-এর packet engine-এ drop।
* **জানা সীমা:** ৪-byte Node ID-র brute-force grinding সম্ভব (কেউ ইচ্ছাকৃতভাবে অন্যের ID-র সঙ্গে মিলিয়ে key বানাতে পারে —
  TOFU সেটি প্রথম-দেখা key দিয়ে ঠেকায়, কিন্তু পূর্ণ সুরক্ষা নয়)। Advertisement-এ স্থির Node ID থাকায় পাশের মানুষ ট্র্যাক করতে পারে
  (rotating ID ভবিষ্যৎ কাজ)।

## 10. Power

| Mode | Scan | Advertise | HELLO |
|---|---|---|---|
| HIGH (chat খোলা) | LOW_LATENCY, continuous | LOW_LATENCY | ৫ সে |
| NORMAL (app সামনে) | BALANCED, ১০ সে on / ৫ সে off | BALANCED | ১০ সে |
| LOW (background) | LOW_POWER, ৬ সে on / ২৪ সে off | LOW_POWER | ২০ সে |

Android scan throttling (৩০ সেকেন্ডে ৫ বারের বেশি start নয়) মেনে scan start গণনা করে delay দেওয়া হয়।

## 11. Database (Room)

`user, node, chat, message, public_post, pending_message`।
**Neighbor ও Route table ইচ্ছাকৃতভাবে in-memory** — পুরোনো topology disk-এ রাখা ক্ষতিকর (stale route)। Debug স্ক্রিনে live দেখা যায়।


---

## 12. Content kinds, media transfer and offline delivery (v1.1)

**Content model.** A private message is `kind(1) + body`, then encrypted as one blob (same ECDH→HKDF→AES-GCM; AAD = src|dst|msgId).

| kind | body |
|---|---|
| TEXT | UTF-8 (≤1000 chars) |
| LOCATION | lat/lon (float64 ×2) + accuracy (int32) = 21 bytes with the kind byte. Opt-in, one recipient, one-time |
| IMAGE | JPEG bytes (≤110 KB after compression; EXIF stripped by re-encode) |
| VOICE | codec(1: 1=Opus/Ogg, 2=AAC/M4A) + durationMs + 40-bar waveform + audio bytes |

**Small content (TEXT, LOCATION)** is one `PRIVATE` packet and uses the pending table (store-and-forward).

**Large content (IMAGE, VOICE)** uses `MEDIA` (type 8) and `MEDIA_NACK` (type 9):
1. Sender encrypts the whole content into one blob (≤170,000 B) and splits it into 900-byte chunks (≤200). Each chunk is a separately routed unicast packet with a fresh msgId, payload `transferId | index | total | bytes`; each is further BLE-fragmented to the MTU.
2. Receiver reassembles (≤4 concurrent incoming, timeout), decrypts, stores the file and sends `ACK(transferId)`.
3. If chunks stall for 8 s the receiver sends `MEDIA_NACK(transferId, missing indexes)` and the sender selectively retransmits.
4. The sender keeps the blob in the persistent `media_out` outbox: retry backoff 30/60/120/240 s, max 5 attempts, 24 h expiry. One media send at a time (mutex); progress is exposed as a StateFlow of `msgId → 0..1`.
5. Relays forward chunks only when a route exists (`forwardNoQueue`); they **never store** media and cannot decrypt it.

**Offline recipient.** The message is saved locally first (status QUEUED, ⏳). Text/location sit in the pending table, image/voice in the outbox. When a route to the destination appears (HELLO/route ad), `flushQueues` = `processPending` + `processTransfers` sends them. `markSent` only upgrades QUEUED→SENT so a quick ACK (DELIVERED) is never overwritten.

**Compression.** Image: decode with downsampling → EXIF rotate → ≤480 px → JPEG quality ladder until ≤48 KB (hard max 110 KB). Voice: `MediaRecorder`, 16 kHz mono, Opus 16 kbps (API 29+) or AAC 24 kbps, ≤60 s, waveform from sampled mic amplitude.

**Why not GPS everywhere?** Broadcasting exact positions of all members of an open community mesh enables stalking, so MeshChat deliberately does not. Location is a user-initiated, per-recipient, encrypted one-shot (see README privacy).

**Throughput budget.** Realistic BLE GATT write throughput with MTU 247 is a few KB/s on one hop and drops per hop; hence the size caps. Announce is text-only.

**UI (WhatsApp-style voice).** Hold-to-record mic (`MicHoldButton`, pointer-input gesture, slide-left cancel), `RecordingBar` (blinking dot, timer, live level), `VoiceBubbleContent` (round play/pause, waveform with progress & seek, duration, speed chip). `VoicePlayer` exposes `StateFlow<PlayerState>`; only one clip plays at a time. Icons are drawn on a Canvas.

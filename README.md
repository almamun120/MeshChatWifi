# MeshChat — Offline BLE Mesh Community Chat (Android)

Internet, mobile data বা কোনো central server ছাড়া কাছাকাছি Android ফোনগুলো Bluetooth Low Energy দিয়ে নিজেদের মধ্যে mesh তৈরি করে।
প্রতিটি ফোন একই সঙ্গে BLE Scanner + Advertiser + GATT Client + GATT Server + Relay Node।

* **Private chat** — end-to-end encrypted (P-256 ECDH → HKDF → AES-256-GCM); relay node plaintext দেখতে পায় না।
* **Announce** — decentralized public community room (signed posts, spam rate-limit, duplicate suppression)।
* **Multi-hop relay** — application-level distance-vector routing, TTL, duplicate cache, ACK + retry।
* **Store-and-forward** — destination না পাওয়া গেলে Room-এ জমে, route আসলে পাঠায়; expiry (২৪ ঘণ্টা) ও সর্বোচ্চ ২০০টি।
* **Sync** — দুই node কাছাকাছি এলে শুধু message ID তালিকা বিনিময়, কেবল অনুপস্থিত post যায়।
* **Privacy-first** — DOB কখনো mesh-এ যায় না; advertisement-এ নাম/DOB/লোকেশন নেই।
* **Photos (new)** — private chat-এ ছবি; auto-compressed (≤480 px JPEG, ~20–48 KB), E2E encrypted, chunked + NACK retransmit।
* **Voice messages (new)** — hold-to-record (slide left = cancel), Opus 16 kbps (Android 10+) / AAC 24 kbps fallback, max 60 s; WhatsApp-style player: play/pause, waveform seek, 1x/1.5x/2x speed।
* **Share location (new, opt-in)** — one-time snapshot to ONE contact, exact or approximate (~1 km), E2E encrypted, behind a consent dialog. Never broadcast, never in Announce, never in advertisements।
* **Offline recipients (new)** — text/photo/voice/location are saved locally (⏳ waiting) and delivered automatically when the recipient becomes reachable (retries 30→240 s, expiry 24 h)।

বিস্তারিত design: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## ⚠️ বর্তমান যাচাই-অবস্থা (সৎ হিসাব)

| অংশ | অবস্থা |
|---|---|
| `core/` (packet codec, fragmentation, crypto, routing, MeshEngine, dedup, TTL, ACK/retry, store-and-forward, sync, spam limit) | ✅ **JVM-এ কম্পাইল ও ৩৪টি test পাস** — যার মধ্যে A→B→C relay, encrypted private chat, Announce flood+dedup, TTL ১০-node লাইন, store-and-forward, late-joiner sync, spam limit, এবং নতুন: encrypted image A→B→C, lost chunk NACK recovery, offline recipient-এর জন্য image জমা রেখে পরে delivery, voice waveform/duration, location, size limit (in-memory radio, কিন্তু আসল engine/codec/fragmentation/signing/encryption)। |
| Room, KeyVault, `BleTransport`, Foreground Service, Compose UI, image/voice/location (`media/`) | ⚠️ **লেখা হয়েছে, কিন্তু আমার পরিবেশে Android SDK/Maven নেই বলে Gradle দিয়ে build করা যায়নি।** কোডটি যত্নসহকারে review করা হয়েছে, তবু Android Studio-তে প্রথম build-এ ছোটখাটো compile error আসতে পারে — সেগুলো পেলে error-সহ জানান, ঠিক করে দেব। |
| আসল BLE radio আচরণ | ❌ **কোনো আসল ফোনে এখনো পরীক্ষা হয়নি।** BLE-তে vendor-ভেদে অনেক পার্থক্য আছে (নিচে "সীমাবদ্ধতা")। নিচের test procedure দিয়ে আপনার ৩টি ফোনে যাচাই করতে হবে। |

## Build ও Run

1. **Android Studio Ladybug (2024.2) বা নতুন** + JDK 17 (Android Studio-র সঙ্গে আসা JBR চলবে)।
2. `File ▸ Open` → `MeshChat` ফোল্ডার। Gradle sync-এ AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35 নামবে (Internet লাগবে — শুধু build-এর জন্য, অ্যাপ চালাতে নয়)।
3. **কমপক্ষে ২টি (ভালো হয় ৩টি) আসল Android ফোন** (Android 8.0+ / API 26+), BLE advertising সমর্থিত। Emulator-এ BLE mesh চলে না।
4. প্রতিটি ফোনে Run ▸ app। অথবা `./gradlew :app:installDebug`।
5. JVM unit test: `./gradlew :app:testDebugUnitTest` (core-এর ২৪টি test, ফোন লাগে না)।

## CI/CD — GitHub Actions (auto build, signed release APK)

* `ci.yml`: push/PR ⇒ unit tests + lint + debug APK artifact.
* `release.yml`: push tag `v1.0.0` ⇒ tests ⇒ **signed** release APK (`apksigner verify`) ⇒ GitHub Release with `MeshChat-1.0.0.apk` + SHA-256।
* এক-বারের setup: `scripts/generate-keystore.sh` চালিয়ে keystore বানান, ৪টি GitHub secret দিন — বিস্তারিত [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md)। **Keystore হারালে app update দেওয়া যাবে না; কখনো commit করবেন না।**

## Permissions (কোন Android সংস্করণে কেন)

| Permission | কখন | কেন |
|---|---|---|
| `BLUETOOTH_SCAN` (`neverForLocation`) | Android 12+ | nearby node খোঁজা। `neverForLocation` নিরাপদ, কারণ আমরা scan থেকে অবস্থান বের করি না; আমাদের নিজস্ব service UUID-তে filter করা হয়, beacon-এ নয়। |
| `BLUETOOTH_ADVERTISE` | Android 12+ | নিজের Node ID advertise। |
| `BLUETOOTH_CONNECT` | Android 12+ | GATT server/client connection, Bluetooth চালু করার prompt। |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | ≤ Android 11 | পুরোনো install-time permission। |
| `ACCESS_FINE_LOCATION` | ≤ Android 11 | OS এই সংস্করণে BLE scan result location permission-এর সঙ্গে বেঁধে রেখেছে; **System Location সুইচও ON লাগে**। অ্যাপ GPS পড়ে না, পাঠায় না। Android 12+-এ লাগেই না। |
| `POST_NOTIFICATIONS` | Android 13+ | Foreground service ও নতুন বার্তার notification। ঐচ্ছিক — না দিলেও mesh চলে। |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 9+/14+ | background-এ mesh জীবিত রাখা (type `connectedDevice`)। |

## Privacy ও Security সংক্ষেপে

* **Node ID** = P-256 public key-র SHA-256-এর প্রথম ৪ byte (৮ hex, `MC-7F3A92B1`)। MAC/IP/GPS-নির্ভর নয়। UI-তে raw MAC কোথাও দেখানো হয় না।
* **Reinstall করলে নতুন Node ID হয়** (private key backup/restore থেকে বাদ — `allowBackup=false`)। সুবিধা: কেউ পুরোনো identity চুরি করতে পারে না। অসুবিধা: পুরোনো chat ও পরিচিতি হারায়। (ভবিষ্যতে passphrase-সুরক্ষিত export যোগ করা যায়।)
* **DOB** শুধু ফোনের Room database-এ; কোনো packet-এ field-ই নেই। "Show DOB" সুইচ শুধু আপনার নিজের Profile স্ক্রিনে দেখানো/লুকানো নিয়ন্ত্রণ করে।
* **GPS / location**: mesh বা advertisement-এ **কখনো automatic না**। শুধু আপনি private chat-এ ＋ ▸ Location চাপলে ও dialog-এ নিশ্চিত করলে একবার fix নিয়ে **শুধু ওই একজনকে** E2E encrypted পাঠানো হয় ("exact" বা "approximate ~1 km")। কোনো live tracking নেই; Announce-এ location পাঠানো যায় না। কারণ: একটি কমিউনিটি mesh-এ সবাইকে সবার সঠিক অবস্থান জানালে stalking/নিরাপত্তা ঝুঁকি — তাই এটি opt-in, ব্যক্তি-নির্দিষ্ট।
* **Photos/voice**: encrypted blob হিসেবে যায়; relay কেবল chunk header দেখে ও কিছু জমা রাখে না। ছবির EXIF/GPS metadata সংকোচনের সময় মুছে যায় (re-encode)। Media ফাইল ফোনের app-private storage-এ থাকে।
* **Profile photo** শুধু ফোনে থাকে; mesh-এ পাঠানো হয় না (BLE bandwidth)। চ্যাটের ছবি আলাদা বিষয় (ওপরে)।
* **যা mesh-এ যায়**: আপনার নাম + public key (signed IDENTITY), Announce post, এবং encrypted private blob।
* **Private message**: relay শুধু header (src, dst, TTL) দেখে। **Forward secrecy নেই** (static ECDH key) — v2-তে ephemeral key/ratchet।
* **জানা দুর্বলতা**: (১) advertisement-এ স্থির Node ID থাকায় পাশের কেউ আপনার ফোনের উপস্থিতি ট্র্যাক করতে পারে (rotating ID ভবিষ্যৎ কাজ); (২) ৪-byte ID-তে ইচ্ছাকৃত ID-collision grinding সম্ভব — TOFU key-pinning আংশিক সুরক্ষা দেয়; (৩) যে নতুন node-এর key আগে দেখা যায়নি, তার Announce "unverified" চিহ্নসহ দেখায়।
* **Age/moderation**: এটি খোলা public room; কোনো admin নেই। প্রকাশ করার আগে আপনার দেশ/স্টোরের নীতি অনুযায়ী বয়সসীমা ও content moderation ভেবে নিন। Block ইতিমধ্যে engine-এ আছে (`setBlocked`); report/mute UI ভবিষ্যৎ কাজ।

## প্রোটোকল ও spec-এর সঙ্গে পার্থক্য (সরাসরি জানাচ্ছি)

* Node ID **৮ hex** (৬ নয়) — collision কমাতে। এখনো `MC-xxxxxxxx` ফরম্যাট।
* **Neighbor ও Route table in-memory**, Room-এ নয় — পুরোনো topology disk-এ রাখলে stale route হয়। বাকি (User, Node, Chat, Message, PublicPost, PendingMessage, MediaOut) Room-এ।
* Android "connect-without-pairing" অনুযায়ী কোনো bonding চাওয়া হয় না; authenticity/secrecy application layer-এ।

---

## ৩টি ফোনে Test Procedure (Phase অনুযায়ী)

নাম দিন **A, B, C**। প্রতিটি ফোনে: install → profile তৈরি (নাম + DOB) → permission দিন → Bluetooth চালু (Android ≤ 11-এ Location সুইচও) → ব্যাটারি settings-এ MeshChat-কে exempt করুন (Me ট্যাবে বোতাম আছে)।
**Me ▸ Developer / debug** স্ক্রিন খোলা রাখলে সব ধাপ ফোনেই দেখা যায়।

### Phase 1 — দুই ফোনে BLE chat (A ↔ B)
1. শুধু A ও B পাশাপাশি রাখুন (≤ ২ মিটার), C বন্ধ।
2. ২০–৩০ সেকেন্ডের মধ্যে Nearby ট্যাবে অন্যজন "1 hop • Online • −xx dBm" দেখাবে। (ছোট Node ID যার, সে connect শুরু করে; বড় জন ~১২ সেকেন্ড অপেক্ষা করে।)
3. Debug ▸ **GATT links: 1**, Advertiser Running, Scanner Running। Neighbors টেবিলে অন্যজনের Node ID, `Link = yes`।
4. "key pending" লেবেল চলে যাওয়া পর্যন্ত অপেক্ষা (IDENTITY বিনিময়)। A-তে B-কে ট্যাপ → **Hello Mesh** পাঠান।
5. প্রত্যাশা: B-তে বার্তা আসে; A-তে অবস্থা ⏳ → ✓ sent → ✓✓ delivered (end-to-end ACK)। Debug: A `Sent 1`, B `Received 1`, A `Delivered 1`।

### Phase 2 — A → B → C relay
1. **A ও C-কে BLE range-এর বাইরে রাখুন**, B মাঝখানে (যেমন আলাদা ঘর/দেয়াল, বা ১৫–২০+ মিটার; B উভয়ের range-এ)। কোণ কেটে নিশ্চিত করুন: A-র Debug ▸ Neighbors-এ **শুধু B** আছে।
2. ~১৫–৩০ সেকেন্ড অপেক্ষা: A-র Debug ▸ Routes-এ `C … 2 hops via B`; Nearby-তে C "2 hops"।
3. A থেকে C-কে private message পাঠান।
4. প্রত্যাশা: C পায়; A-তে ✓✓ delivered। **B-র Debug**: `Relayed` বাড়ে, কিন্তু B-র Chats ট্যাবে কিছু নেই (plaintext পড়তে পারেনি)।

### Phase 3 — Multi-hop discovery
চার ফোন থাকলে লাইন A–B–C–D; A-র Routes-এ B=1, C=2, D=3 hop। ৩ ফোনে: B বন্ধ করলে ≤ ৭৫ সেকেন্ডে (বা link ভাঙার সঙ্গে সঙ্গেই) A থেকে C-র route মুছে যায়; B চালু করলে ফিরে আসে। **Me ▸ Mesh visualization**-এ রিং-ভিত্তিক topology দেখুন।

### Phase 4 — Announce (A posts → B relays → C receives)
1. Phase 2-র অবস্থান (A–B–C লাইন) রেখে A-তে Home ▸ **Announce** → একটি বার্তা লিখে পাঠান।
2. প্রত্যাশা: B ও C-তে Announce ফিডে আসে (✓ signed লেবেলসহ), **C-তে মাত্র একবার**।
3. Debug ▸ Messages: B ও C-তে `Dropped` বাড়তে পারে, `Last drop: duplicate` — মানে একই post দ্বিতীয়বার পেয়ে ফেলে দেওয়া হয়েছে (loop/storm আটকানো কাজ করছে)।
4. Spam test: ১০টি বার্তা দ্রুত পাঠান → প্রাপক শুধু প্রথম ~৫টি রাখে, বাকি `spam: rate limit` drop।

### Phase 5 — Store-and-forward ও sync
1. Phase 2 আগে করা থাকতে হবে (A জানে C-র key)। C-র Bluetooth বন্ধ করুন।
2. A থেকে C-কে বার্তা পাঠান → ⏳ queued; Debug ▸ `Pending (store-and-forward): 1`।
3. C-র Bluetooth চালু করে B-র কাছে আনুন। প্রত্যাশা: ~৩০–৯০ সেকেন্ডে C পায়, A-তে ✓✓ delivered, Pending 0।
4. Announce sync: C বন্ধ থাকা অবস্থায় A Announce করুন; C ফিরে এলে link-up হওয়ার সঙ্গে সঙ্গে inventory বিনিময় হয়ে পোস্টটি আসে (Debug ▸ `Synced out` বাড়ে)।
5. Expiry: pending message ২৪ ঘণ্টা পর `failed`; ৫ বার ACK না এলে `failed`।

### Phase 5b — Photo, voice, location, offline delivery (new)
1. **Photo A→B (direct)**: A-তে Chat ▸ ＋ ▸ Photo, একটি ছবি বাছুন → "Compressing photo…" → বুদবুদে ⏳ NN% → ✓✓। প্রত্যাশা: ~30 KB ছবি কাছাকাছি দুই ফোনে ~১০–৪০ সেকেন্ড। B-তে ছবি দেখা যায়, ট্যাপ করলে full-screen।
2. **Photo A→B→C**: A–C আলাদা করুন (Phase 2-এর মতো)। ছবি পাঠান; B-র Debug-এ `Relayed` বাড়ে কিন্তু B-র Chats-এ ছবি নেই। প্রত্যাশা: প্রতি hop-এ সময় বাড়ে (~১.৫–২×)।
3. **Voice**: ৫–১০ সেকেন্ড mic বাটন **ধরে রেখে** বলুন, ছেড়ে দিন → পাঠানো হয়। বাম দিকে ~90 dp slide করলে বাতিল ("Release to cancel")। ৬০ সেকেন্ডে auto-send।
4. **Voice player (B-তে)**: ▶ চাপুন → চলে, ⏸ → থামে; waveform-এ ট্যাপ/টানলে seek; speed chip 1x→1.5x→2x; অন্য clip চালালে আগেরটি থামে; শেষ হলে শুরুতে ফেরে।
5. **Offline recipient**: C-র Bluetooth বন্ধ। A থেকে C-কে text + ছবি + voice পাঠান → সব ⏳ "waiting (saved…)"। C-র Bluetooth চালু করে B-র কাছে আনুন → ~১–৩ মিনিটে সব পৌঁছায়, A-তে ✓✓। A-র app বন্ধ করে খুলেও (service না থাকলে) pending/outbox থেকে আবার চেষ্টা হয়।
6. **Location**: ＋ ▸ Location → dialog (consent) → "approximate" বা "exact" → permission → B-তে coordinates card + "Open in map" + "Copy"। Approximate-এ ≥1100 m accuracy দেখায়।
7. **Chunk loss**: ছবি পাঠানোর মাঝখানে B-C দূরে সরিয়ে আবার কাছে আনুন — receiver ৮ সেকেন্ড পর NACK পাঠায়, শুধু হারানো chunk পুনঃপ্রেরিত হয় (Debug stats দেখুন)।

### Phase 6 — Encryption যাচাই
* B-র Debug ও Chats থেকে নিশ্চিত: relay করা private message B-তে plaintext হিসেবে কোথাও নেই।
* সিদ্ধান্তমূলক প্রমাণ `CryptoTest.endToEndEncryptionOnlyRecipientCanRead` (তৃতীয় পক্ষ/টেম্পারড ciphertext decrypt হয় না) — `./gradlew :app:testDebugUnitTest`।

### Phase 7 — Power
* অ্যাপ সামনে থাকলে NORMAL (balanced scan ১০s চালু/৫s বন্ধ); chat/Announce খোলা থাকলে HIGH (continuous); background/screen-off-এ LOW (৬s/২৪s)।
* যাচাই: `adb shell dumpsys bluetooth_manager | grep -i scan` এবং Settings ▸ Battery ▸ MeshChat। ১ ঘণ্টা background রেখে ব্যাটারি ব্যবহার দেখুন।

### Phase 8 — UI
Home (Announce/Nearby/Chats কার্ড, "● Mesh: N nodes"), Nearby (hops, Online/Last seen, RSSI), Chats (unread ব্যাজ), Me (DOB সুইচ, Node ID, mesh status), Debug, Topology — সব স্ক্রিন ঘুরে দেখুন; ফোন ঘোরান; Light/Dark দেখুন।

---

## Troubleshooting: "waiting" থেকে যায় / link ওঠে না

1. দুই ফোনেই **Me ▸ Debug** খুলুন। `GATT links` ≥ 1 না হলে বার্তা যাবে না — ⏳ waiting মানে "সংরক্ষিত, link নেই"।
2. Debug-এর নিচে **Link log**: `scan: found …` (প্রতিবেশী দেখা গেছে), `connecting …`, `gatt client state … status=…` (status 133/8 = Android BLE ত্রুটি), `link UP/DOWN`, `media … send start / write FAILED / INTERRUPTED`, `link … dropped: …`। **Copy full link log** চাপে দুই ফোনের log পাঠান।
3. PC থাকলে: `adb logcat -s MeshBle:I` (বার্তার লেখা/key কিছু log হয় না, শুধু Node ID-র ৮ অক্ষর ও অবস্থা)।
4. দুই ফোনে Bluetooth ON, Nearby devices permission, Battery "Unrestricted" (Samsung: Sleeping apps থেকে বাদ) নিশ্চিত করুন; অ্যাপ foreground-এ রাখুন।

## Android-এর বাস্তব সীমাবদ্ধতা (fake করা হয়নি)

* **Background execution**: Foreground Service ছাড়া Android 8+ কয়েক মিনিটে process থামায়। আমরা `connectedDevice` FGS চালাই (notification দেখা যায়)। Android 12+-এ FGS **শুধু visible Activity থেকে** শুরু করা যায় — তাই রিবুটের পর অ্যাপ একবার খুলতে হয় (auto-start-on-boot নেই)।
* **OEM killer** (Xiaomi, Huawei, Oppo, Vivo, Samsung "sleeping apps" ইত্যাদি) FGS-ও মারতে পারে। Battery optimisation exempt করুন ও vendor-এর "autostart/lock in recents" চালু করুন; এটি আমাদের নিয়ন্ত্রণের বাইরে। Dont Kill My App সাইট দেখুন।
* **Scan throttling**: Android ৩০ সেকেন্ডে ৫ বারের বেশি scan start অনুমোদন করে না; আমরা start গণনা করে delay দিই। Screen-off-এ scan ধীর/ফিল্টার-বাধ্য।
* **Connection limits**: ডিভাইসভেদে একসঙ্গে ~৪–৭টি GATT link; আমরা সর্বোচ্চ ৫টি রাখি। Connection attempt একবারে একটি (status 133 প্রতিরোধে), ব্যর্থ হলে backoff।
* **GATT**: একসময়ে একটি operation; MTU পাওয়া না গেলে ২৩ (১৭ byte chunk) — ধীর কিন্তু সঠিক fragmentation। Throughput কম (বাস্তবে ~১–৪ KB/s, প্রতি hop-এ কমে)। তাই media-র কঠোর সীমা: ছবি ≤110 KB (লক্ষ্য ≤48 KB, 480 px), voice ≤60 s (~2 KB/s ⇒ ~১০ সেকেন্ডে ~২০ KB), একটি media transfer একবারে একটি। Announce **শুধু text**।

## Media-র অতিরিক্ত সীমাবদ্ধতা (সৎ হিসাব)

* **Relay media জমা রাখে না।** Store-and-forward-এ বার্তা *প্রেরকের* ফোনে থাকে (outbox); প্রেরকের app/ফোন বন্ধ থাকলে বা প্রেরক রেঞ্জের বাইরে গেলে ছবি/voice পৌঁছাবে না। Text-ও একই (pending table, ২৪ ঘণ্টা, সর্বোচ্চ ২০০টি)। মাঝের ফোন (B) শুধু live relay করে।
* **Voice codec**: Opus/Ogg `MediaRecorder` Android 10+ (API 29)-এ; তার নিচে AAC/M4A 24 kbps। প্রেরক-প্রাপক ভিন্ন সংস্করণ হলে প্রাপকের `MediaPlayer` ফাইলটি চালাতে পারে — Android 10-এর আগের ফোনে Ogg/Opus চালানো অসমর্থিত হতে পারে (Android 5+ সাধারণত Opus/Ogg চালায়, তবু ডিভাইসে পরীক্ষা করুন)।
* **Location permission & store policy**: `ACCESS_FINE_LOCATION` এখন সব Android সংস্করণে declare আছে এবং শুধু user-initiated share-এ পড়া হয়। Google Play-তে প্রকাশ করলে Location permission declaration ও privacy policy লাগবে। GPS fix ঘরের ভিতরে ধীর/অসম্ভব হতে পারে (২০ সেকেন্ড timeout)। Play Services ব্যবহার করা হয়নি (LocationManager)।
* **Voice/photo পাঠানো হচ্ছে এমন সময়ে** প্রেরক অ্যাপ বন্ধ করলে transfer থামে, তবে outbox থেকে পরে আবার শুরু হয়।
* **Advertise + Scan একসঙ্গে**: বেশিরভাগ আধুনিক ফোনে চলে; কিছু পুরোনো চিপে advertising সমর্থিত নয় (`BLE advertising not supported` দেখাবে)। ৩১ byte-এ না আঁটলে manufacturer data scan-response-এ যায় (fallback আছে)।
* **Duplicate link**: দুই ফোন একই সময়ে একে অপরকে connect করলে নির্ধারিত নিয়মে (ছোট ID-র initiated link) একটি রাখা হয়।
* **iOS**: সমর্থিত নয় (আলাদা protocol/background নিয়ম)।
* **Bluetooth Mesh (SIG) নয়**: এটি নিজস্ব application-level GATT mesh; SIG Mesh profile-এর সঙ্গে সামঞ্জস্যপূর্ণ নয়।
* **Throughput/স্কেল**: flooding + ৪০টি route ad + ৬০ post inventory-র সীমা দেওয়া আছে; ৫০+ node-এ আলাদা tuning/তাত্ত্বিক বিশ্লেষণ লাগবে।

## প্রজেক্ট কাঠামো

```
MeshChat/
├─ settings.gradle.kts · build.gradle.kts · gradle.properties · gradlew
├─ docs/ARCHITECTURE.md
└─ app/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/AndroidManifest.xml, res/
      ├─ main/java/com/meshchat/
      │   ├─ core/      Protocol, Fragmenter, Crypto, Caches, Tables, MeshEngine, Transport, Store   (pure Kotlin, JVM-testable)
      │   ├─ ble/       BleTransport (advertiser, scanner, GATT server+client), BlePermissions
      │   ├─ data/      Entities, Daos, MeshDatabase, RoomMeshStore, KeyVault
      │   ├─ repo/      MeshRepository
      │   ├─ service/   MeshService (foreground), Notifier
      │   └─ ui/        Compose screens, MainViewModel, theme
      └─ test/java/com/meshchat/core/   ২৪টি JVM test + in-memory radio (FakeNet)
```

## পরের ধাপ (roadmap)
Forward-secret private chat (ephemeral keys) · rotating advertisement ID · report UI · profile photo-র compressed thumbnail sync · Wi-Fi Aware/Nearby transport (বেশি bandwidth) · adaptive route-ad যুক্তি বড় mesh-এর জন্য।

## Calls & File Share
Audio/video calls (WebRTC) and ShareIt-style file transfer over BLE-signalled Wi-Fi Direct. See docs/CALLS.md.

## নতুন ফিচার (v2.11) — LoRa রিলে শনাক্ত ও LoRa-পথে অসম্ভব ফিচার বন্ধ

- **LoRa রিলে শনাক্ত:** রিলে নিজের বিজ্ঞাপনে `LORA` ক্যাপ বিট (৮) পাঠায়। অ্যাপ সেটা দেখে রিলেকে চেনে (`MeshEngine.isBridge`)। কানেক্ট হওয়ার আগেই চেনা যায়।
- **LoRa-পথ চিহ্ন:** রিলে তার HELLO-তে প্রতিটি দূরের নোডের সাথে একটি চিহ্ন (হপ বাইটের উপরের বিট) পাঠায়: "এই নোডে যেতে LoRa রেডিও পার হতে হয়"। ফোনগুলো চিহ্নটা এগিয়ে দেয়। একই রিলের BLE-তে থাকা ফোনে চিহ্ন থাকে না, তাই সেখানে সব ফিচার চলে।
- **যা বন্ধ হয় (LoRa-পথের নোডের জন্য):** ছবি, ভয়েস মেসেজ, PTT (ক্লিপ ও লাইভ), অডিও/ভিডিও কল, ফাইল পাঠানো। বাটন ধূসর/লুকানো, কারণ চ্যাটের উপরে লেখা থাকে। ইঞ্জিনও `LORA_PATH` ফেরত দেয়। টেক্সট ও লোকেশন চলে।
- **ধীর পথের রিট্রাই:** রিলে হয়ে যাওয়া মেসেজের পুনঃচেষ্টা ৪ গুণ ধীর (রিলে নিজেই LoRa-তে রিট্রাই করে), যাতে রেডিও ভরে না যায়।
- **রিলেকে সিঙ্ক পাঠানো হয় না:** রিলে নিজে LoRa-তে পোস্ট পাঠায়।
- **পুরোনো ভার্সন (1.x) আর সাপোর্ট করা হয় না:** v2 TTL ১৬ পর্যন্ত পাঠায় ও HELLO-র হপ বাইটের উপরের বিট ব্যবহার করে, 1.x ফোন এগুলো পড়তে পারবে না। সবাইকে আপডেট করুন।
- রিলে ফার্মওয়্যার ১.২ লাগবে (`MeshChat-LoRa-Relay-v1.2`) পূর্ণ সুবিধার জন্য: পুরোনো রিলে চলবে, কিন্তু চেনা যাবে না ও পরিচয়-অনুরোধ জানবে না।

## নতুন ফিচার (v2.00) — ভিড়ে ফ্লাড নিয়ন্ত্রণ ও "Mesh traffic" সেটিং

পাবলিক পোস্ট ও পরিচয় (নাম + কি) প্রতিটি ফোন আবার ছড়িয়ে দেয় (ফ্লাড)। ফোন কম থাকলে এটা ঠিক, কিন্তু স্টেডিয়ামের মতো ভিড়ে ব্লুটুথ জ্যাম করে দেয়। v2.00-এ এটা নিজে থেকে নিয়ন্ত্রিত হয়, আর চাইলে হাতে বদলানো যায় (**Settings → Mesh traffic**)।

**কী কী বদলেছে**
- **অটো মোড (ডিফল্ট):** ফোন আশেপাশে কতগুলো ফোন শুনছে (ঘনত্ব) মেপে নিজের নিয়ম বদলায়: Sparse (≤৬), Normal (≤২০), Dense (≤৬০), Crowded (৬০-এর বেশি)।
- **TTL ক্ল্যাম্প:** ভিড়ে প্রতিটি ফোন পাশ দিয়ে যাওয়া ব্রডকাস্টের বাকি হপ ছোট করে দেয় (ঘন জায়গায় ২ থেকে ৩ হপ)। একজনের ভুল সেটিং তাই পুরো এলাকার ক্ষতি করতে পারে না।
- **র‍্যান্ডম বিরতি + "আগেই শুনেছি" নিয়ম:** ব্রডকাস্ট পেয়ে ফোন সাথে সাথে পাঠায় না। কিছুক্ষণ অপেক্ষা করে, আর এর মধ্যে অন্য প্রতিবেশীদের কাছ থেকে k-টি কপি শুনলে নিজের রিলে বাতিল করে।
- **পাবলিক পোস্টে হার সীমা:** নিজের পোস্ট এবং অন্যের পোস্ট রিলে করা, দুটোতেই একই সীমা (বার্স্ট + ধীর রিফিল)। ভিড়ে ৫ মিনিটে ১টি।
- **পরিচয় শুধু প্রতিবেশীকে (ঘন জায়গায়):** দূরের কারো সাথে চ্যাট শুরু করলে অ্যাপ নতুন `IDENTITY_REQ` বার্তায় তার পরিচয় চেয়ে নেয় এবং নিজের পরিচয় সাথে পাঠায়। প্রথম মেসেজ ২ থেকে ৪ সেকেন্ড দেরি হতে পারে।
- **প্রাপকের দিকে সুরক্ষা:** প্রেরকের কি না থাকলে প্রাইভেট মেসেজ ফেলে না দিয়ে কিছুক্ষণ রেখে দেয়, পরিচয় এলে খুলে দেখায়। (রিলেগুলো প্রেরকের পুনঃপ্রেরণকে ডুপ্লিকেট ভেবে ফেলে দেয়, তাই পুনঃপ্রেরণের ওপর ভরসা করা যেত না।)
- **SOS ব্যতিক্রম:** SOS ক্ল্যাম্প বা সাপ্রেশনে আটকায় না, শুধু ছোট (সর্বোচ্চ ২০০ ms) বিরতি পায়।
- **হপ সীমা ১৬, ডিফল্ট TTL ১৫।**

**ম্যানুয়াল মোড:** চারটি প্রিসেট (Small group, Crowd, Stadium, Long range) আর আলাদা আলাদা স্লাইডার: Reach (হপ ১ থেকে ১৬), "Stay quiet after hearing" (k), র‍্যান্ডম বিরতি, পাবলিক পোস্টের বার্স্ট ও রিফিল, পরিচয় ছড়ানো চালু/বন্ধ ও কত পর পর। প্রতিটি মান সীমার ভেতরে আটকানো (`FloodPolicy.sanitize`)।

**সীমাবদ্ধতা (সৎভাবে)**
- ভিড়ের ঘনত্বের সীমা (৬/২০/৬০) ও প্রিসেটের মান আমার অনুমান; বাস্তব ফোনে মেপে ঠিক করা দরকার।
- ভিড়ে দূরের মানুষকে পাওয়া তবুও কঠিন: প্রতি ফোনে ৫টি সংযোগ ও ছোট TTL। এই ডিজাইন ব্যবস্থাকে ভেঙে পড়া থেকে বাঁচায়, দূরত্ব বাড়ায় না।
- **v2.00 ফোন পুরোনো (v1.x) ফোন ও LoRa রিলের সাথে চলে**, কিন্তু `IDENTITY_REQ` সেগুলো বোঝে না (ফেলে দেয়) এবং LoRa রিলে ক্ল্যাম্প ও সাপ্রেশন করে না।
- Settings পেজ (Compose) এই পরিবেশে বিল্ড করে দেখা যায়নি; কোর লজিকের ১৩৪টি JVM টেস্ট পাস করেছে।

## নতুন ফিচার (v1.1) — chat, group, SOS, push-to-talk, identity backup
বিস্তারিত: [docs/FEATURES.md](docs/FEATURES.md)।

- **Identity backup (JSON):** Settings → Export identity backup (পাসফ্রেজ দিয়ে AES-256-GCM, PBKDF2 210k)। নতুন install-এর প্রথম স্ক্রিনে "Restore from backup" দিলে পুরোনো Node ID ফেরে।
- **Chat:** reply (quote), delete (নিজের ফোনে / সবার জন্য — শুধু নিজের পাঠানো মেসেজ), search (চ্যাটের ভেতরে ও সব চ্যাটে), pin, mute, disappearing messages (৫ মিনিট–৭ দিন)।
- **Private group:** সর্বোচ্চ ৮ জন; প্রতিটি মেসেজ প্রতি সদস্যকে আলাদা E2E-encrypted প্যাকেটে যায়।
- **SOS:** signed BLE flood, ~৪৫ সেকেন্ড পরপর পুনরাবৃত্তি, "I'm safe" না দেওয়া পর্যন্ত; location ঐচ্ছিক; রিসিভার Settings-এ বন্ধ রাখতে পারে।
- **Push-to-talk:** (ক) ≤১৫ সেকেন্ডের ক্লিপ BLE mesh-এ (📻 বোতাম), (খ) লাইভ walkie-talkie Wi-Fi Direct-এ (chat মেনু)। রিসিভার Settings → Push-to-talk থেকে বন্ধ করতে পারে।

**যাচাই-অবস্থা:** এই ফিচারগুলোর `core/` অংশ JVM test-এ পাস। Android UI/Room/Notification অংশ আমার পরিবেশে build বা ফোনে চালানো হয়নি।

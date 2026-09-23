# Blackout Comms ↔ Blackout Comms Live  
## BLE Interface Message Catalog

Source of truth: `BlackoutCommsLive` (`BleFeedManager.kt`, `ConnectionService.kt`, `ClusterRepository.kt`, `Models.kt`, `SendMessageManager.kt`, `NeighborsDeserializer.kt`, `app/src/main/assets/test_data/`).

This document describes the **phone-to-communicator BLE link**, not the LoRa mesh protocol. The Android app never talks to the mesh directly. It attaches to one communicator over GATT; that communicator is the bridge.

---

## 1. Roles and transport

| Role | Device | GATT role |
|------|--------|-----------|
| Communicator (Blackout Comms firmware) | T-Deck, Lilygo Pager, Heltec v4, etc. | Peripheral / GATT server |
| Companion (Blackout Comms Live) | Android phone / tablet | Central / GATT client |

Advertised name filter used by the app: prefix `BC-`.

### GATT objects

| Object | UUID | Direction | Properties expected by the app |
|--------|------|-----------|--------------------------------|
| Service | `18aeec00-8c60-411b-b958-78c5049be0f3` | — | Required |
| TX characteristic | `18aeec01-8c60-411b-b958-78c5049be0f3` | App → device | Write (default / with response) |
| RX characteristic | `18aeec02-8c60-411b-b958-78c5049be0f3` | Device → app | Notify **or** Indicate |

If the service or either characteristic is missing, or RX has neither Notify nor Indicate, the app marks the device `NOT_SUPPORTED` and disconnects.

### Link setup (app side)

1. Scan for names starting with `BC-`.
2. Connect GATT (direct first, then OS `autoConnect` retry).
3. Discover services; require the UUIDs above.
4. Request MTU **247**. Rejection is tolerated; default ATT MTU is used.
5. Enable notifications/indications on RX.
6. If the user supplied a PIN, write `PIN:<pin>` on TX.
7. Reassemble RX bytes into newline-terminated frames and ingest.

Bonding is **not** initiated by the app. If the firmware requires pairing, Android starts it when `enableNotifications` returns insufficient authentication.

The same newline-delimited JSON feed is also used over USB serial (115200 8N1). Message bodies are identical; only the physical layer differs.

---

## 2. Framing

All application frames on both characteristics are **UTF-8 text**.

| Rule | Detail |
|------|--------|
| Record delimiter | `LF` (`\n`, `0x0A`) |
| Encoding | UTF-8 |
| Typical payload | One JSON object per line |
| Exception | Plain-text PIN result `success\n` |
| Fragmentation | One JSON object may span many GATT notifications. The app concatenates chunks until it sees `\n`, then parses the whole buffer. |
| Outbound JSON | Written as a single string on TX. `sendJson()` currently does **not** append `\n` (comment in code says the firmware line reader needs one — firmware and app should stay in sync on this). PIN writes also do **not** append `\n`. |

Malformed JSON lines are dropped silently.

The app identifies an inbound JSON message by **which top-level key is present**, in this order:

`self` → `devices` → `neighbors` → `location` → `graph` → `sender` → `message` → `traffic` → `messageStatus` → `conn`

A payload that is a message object with a top-level `sender` (no wrapping `message` key) is treated as a message.

---

## 3. Session / authentication messages

### 3.1 `PIN:<pin>` — App → Device

Unlocks the live feed after GATT is ready.

| | |
|--|--|
| Characteristic | TX |
| Format | Plain text, not JSON |
| Body | `PIN:` + PIN digits/string shown on the communicator |
| Example | `PIN:1234` |
| When | Immediately after `CONNECTED` / `onDeviceReady` if a PIN was entered or saved |

The app then waits up to **5 seconds** for any inbound data. First valid ingest (JSON or `success`) marks the PIN verified and the credentials are saved. Disconnect during the wait, or timeout with no data, is treated as PIN failure.

### 3.2 `success` — Device → App

Application-level PIN accepted.

| | |
|--|--|
| Characteristic | RX |
| Format | Literal string `success` plus newline |
| Example | `success\n` |

`BleFeedManager` special-cases this string and forwards it to `ClusterRepository.ingest()` before (and in addition to) normal line assembly. It is **not** JSON. Any subsequent JSON feed is the real confirmation the PIN path uses.

### 3.3 `dump` — App → Device (legacy / unused)

Commented-out path in `BleFeedManager.onDataReceived`. Historical idea: after the first RX chunk, write `dump` on TX to request a full state snapshot. **Not sent by current Live builds.** Firmware may still honor it.

---

## 4. Device → App messages (live feed)

All of these are JSON objects, one object per newline-terminated frame. Field types below are what the app accepts. Many numeric fields are sent as **strings** (firmware habit); the app parses them.

Timestamps are commonly compact `YYMMDDHHmmss` (example `260513155900` = 2026-05-13 15:59:00). Some payloads may use `yyyy-MM-dd HH:mm:ss`. The app stores the raw string.

---

### 4.1 `conn` — radio / link status of the connected device

Upserted; only the latest instance is kept. Drives toolbar net name, frequency, stealth, GPS-age coloring.

```json
{
  "conn": {
    "net": "BC",
    "cfg": "LoRa@913.3 S+",
    "q": 5,
    "stlth": "n",
    "la": 0
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `net` | string | Network / mode label. Comment in code: `BC`, `BCM`, or `M`. |
| `cfg` | string | Radio config. Frequency is parsed from `@<mhz>` (example `913.3` from `LoRa@913.3 S+`). |
| `q` | int | Link quality 0–5 (UI buckets: 0–3 poor, 4–6 fair, else good). |
| `stlth` | string (1 char) | Stealth: `n` none, `p` low, `u` medium, `s` high. |
| `la` | int | Location age (seconds implied). UI: ≤120 green, >6000 gray, else yellow. |

---

### 4.2 `self` — identity and vitals of the connected communicator

Also upserts that device into the cluster roster.

```json
{
  "self": {
    "cluster": "desert-ops",
    "id": "NV001",
    "address": "001",
    "icon": "user",
    "batteryLevel": "high",
    "name": "Condor1",
    "lat": "38.0721",
    "lon": "-117.2283",
    "alt": "1588.0",
    "head": "90.0",
    "speed": "0.0",
    "ts": "260513155900",
    "relayState": "on",
    "motion": "still",
    "temperature": "28.5"
  }
}
```

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `id` | string | yes | Cluster device id (used as map / message key). |
| `address` | string | yes | Short mesh address (often zero-padded decimal). Graph keys use the numeric form without padding. |
| `icon` | string | yes | Device class: `user`, `root`, `node`, `relay`, `proximity`, `thermal`, … |
| `name` | string | yes | Display name. |
| `lat`, `lon` | string | yes | WGS84 decimal degrees. |
| `ts` | string | yes | Fix / self timestamp. |
| `cluster` | string | no | Cluster name. |
| `batteryLevel` | string | no | Qualitative battery (`high` / `medium` / `low` in samples). |
| `alt` | string | no | Altitude. |
| `head` | string | no | Heading, degrees. |
| `speed` | string | no | Speed. |
| `relayState` | string | no | `on` / `off`. |
| `motion` | string | no | Motion label. |
| `temperature` | string | no | Device temperature. |

---

### 4.3 `devices` — cluster roster

Full or incremental roster. Existing entries are updated in place; unknown ids are created. Locations that arrived before the roster entry are applied from a queue.

```json
{
  "devices": [
    {
      "id": "NV002",
      "name": "Condor2",
      "nickname": "C2",
      "address": "002",
      "critical": false,
      "icon": "user"
    }
  ]
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string | Device id. |
| `name` | string | Canonical name. |
| `nickname` | string? | Short label; UI prefers this when non-blank. |
| `address` | string | Mesh address. |
| `critical` | bool | Highlight as a critical asset. Default `false`. |
| `icon` | string | Device class (see `self.icon`). Relays / nodes / proximity sensors are hidden from the DM recipient picker. |

---

### 4.4 `location` — position batch (full or delta)

```json
{
  "location": [
    {
      "id": "NV002",
      "lat": "38.0798",
      "lon": "-117.2156",
      "head": "225.0",
      "speed": "2.1",
      "ts": "260513155855"
    }
  ]
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string | Must match a roster id. If the roster entry is not present yet, the fix is queued. |
| `lat`, `lon` | string | WGS84. |
| `head` | string? | Heading degrees. |
| `speed` | string? | Speed. |
| `ts` | string | Fix time. First entry’s `ts` is published as the “last location update” status. |

May contain one device or the whole cluster.

---

### 4.5 `neighbors` — RF neighbors of the connected device

Drives direct/indirect range rings, vitals overlays, and the ping log.

```json
{
  "neighbors": {
    "direct": [
      {
        "id": "NV002",
        "ts": "260513155858",
        "rssi": -52,
        "battery": "high",
        "temperature": 27.1,
        "motion": "moving",
        "relayState": "off"
      }
    ],
    "indirect": [
      {
        "id": "NV003",
        "ts": "260513155848",
        "rssi": -83,
        "battery": "high"
      }
    ]
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `neighbors.direct` | array **or** object | Devices heard over RF by the connected communicator. |
| `neighbors.indirect` | array **or** object | Devices known via mesh memory / multi-hop, not currently a direct RF neighbor. |
| `id` | string | Mandatory. |
| `ts` | string? | Sighting time (`YYMMDDHHmmss` or full datetime). |
| `rssi` | int or numeric string | dBm. Shown on the ping list. |
| `battery` | string? | Qualitative battery. |
| `temperature` | number **or** string | Firmware sends both; app accepts either. |
| `motion` | string? | Motion label. |
| `relayState` | string? | `on` / `off`. |

Firmware quirks the app already handles:

- `direct` / `indirect` may be omitted.
- Either side may be a JSON **object with duplicate or index keys** instead of an array.
- A later `neighbors` payload does **not** clear previous `DIRECT` flags globally; direct ids are aged out after 10 minutes of no refresh.

Each entry also becomes a `PingEntry` (newest first, cap 30).

---

### 4.6 `graph` — cluster RF topology

Keys are **address numbers as strings**, not device ids. Sample data uses `"1"`, `"2"`, … matching `address` with leading zeros stripped. Values are upserted; a partial graph only refreshes the edges it carries.

```json
{
  "graph": {
    "1": {
      "2": { "direct": 88, "indirect": 2, "age": 120 },
      "4": { "direct": 72, "indirect": 3, "age": 95 }
    }
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `graph[<fromAddr>][<toAddr>]` | object | Directed edge from → to. |
| `direct` | int | Direct-link strength 0–100 (UI color bands: 80–100 strong, 50–79 good, 30–49 weak, 6–29 poor, ≤5 marginal). |
| `indirect` | int | Indirect / multi-hop strength or hop-ish metric. |
| `age` | long | Age of the relationship (seconds in samples). |

---

### 4.7 `message` / flat message — chat item

Two accepted shapes.

**Wrapped**

```json
{
  "message": {
    "id": "msg001",
    "sender": "NV004",
    "recipient": "NV001",
    "delivery": "direct",
    "status": "delivered",
    "ts": "260513155910",
    "title": "Status from Eagle1",
    "text": "Sector North is clear.",
    "isNew": true,
    "priority": "Normal"
  }
}
```

**Flat** (test fixtures and some firmware builds — detected via top-level `sender`)

```json
{
  "id": "msg002",
  "sender": "NV006",
  "recipient": "[all devices]",
  "delivery": "mesh",
  "status": "confirmed",
  "ts": "260513155915",
  "title": "Broadcast from BaseAlpha",
  "text": "All units: wind picking up from the west.",
  "isNew": true,
  "priority": "High"
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string? | Firmware message id. Dedup key is `id\|recipient`, or `sender_ts\|recipient` if `id` is absent. |
| `sender` | string | Sender device id. |
| `recipient` | string | Target device id, or `[all devices]` for broadcasts. |
| `delivery` | string | `direct` or `mesh`. |
| `status` | string | Lifecycle. Known values: `queued`, `delivered`, `confirmed`, `meshaccepted`, `deleted`. Case-insensitive `deleted` **removes** the message from live and saved maps. |
| `ts` | string | Send / receive timestamp. |
| `title` | string | Short title. |
| `text` | string | Body (plaintext after firmware decrypt). |
| `isNew` | bool? | `false` → historical only (saved list). `true` or omitted → live + saved. |
| `priority` | string? | `Low`, `Normal`, `Medium`, `High`, `Critical`. |

---

### 4.8 `messageStatus` — delivery-state patch

Does not carry body text. Updates or deletes an existing message by `id` + `recipient`.

```json
{
  "messageStatus": {
    "id": "msg001",
    "sender": "NV004",
    "recipient": "NV001",
    "status": "confirmed"
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string | Must match the original message id. |
| `sender` | string | Original sender. |
| `recipient` | string | Same recipient used in the dedup key. |
| `status` | string | New status, or `deleted` to drop the message. |

If the message is not in memory yet, the status is ignored (no placeholder is created).

---

### 4.9 `traffic` — mesh byte / packet counters

Snapshot of the connected device’s RF accounting since boot (or since firmware reset). The app stamps arrival time and keeps a 20-minute rolling window for the traffic chart.

```json
{
  "traffic": {
    "bytesIn": 18420,
    "bytesOut": 9240,
    "packetsIn": 114,
    "packetsOut": 38,
    "unknownPackets": 0
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `bytesIn` | long | Bytes received on the mesh radio. |
| `bytesOut` | long | Bytes transmitted. |
| `packetsIn` | long | Packets received. |
| `packetsOut` | long | Packets transmitted. |
| `unknownPackets` | long | Optional; older firmware omits it (default 0). |

---

## 5. App → Device messages (commands)

Written on the TX characteristic as UTF-8. Current Live builds send three command families (PIN is covered in §3).

---

### 5.1 `bc` — send a cluster broadcast

Built by `SendMessageManager.buildBroadcast()`. Firmware encrypts, signs, and floods according to cluster broadcast rules.

```json
{
  "bc": {
    "msg": "All units hold position.",
    "nodes": true,
    "priority": "Normal",
    "expiry": 60
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `msg` | string | Body. App sanitizes to letters, digits, space, `.`, `,` and caps at 255 chars. |
| `nodes` | bool | UI label “+ Links”. When true, include relay/node infrastructure in delivery. Default checked in the compose dialog. |
| `priority` | string | `Low` \| `Normal` \| `Medium` \| `High` \| `Critical`. |
| `expiry` | int | Minutes. App offers 5, 10, 30, 60, 120. Broadcast max in the UI is 2 hours. |

---

### 5.2 `dm` — send a direct message

Built by `SendMessageManager.buildDirectMessage()`. Firmware encrypts to the recipient and routes direct-first unless forced to mesh.

```json
{
  "dm": {
    "to": "NV004",
    "msg": "Moving to checkpoint Bravo.",
    "priority": "Normal",
    "fm": false,
    "expiry": 30
  }
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `to` | string | Recipient **device id** (not address). |
| `msg` | string | Same charset / 255-char limit as broadcast. |
| `priority` | string | Same enum as broadcast. |
| `fm` | bool | Force mesh (skip opportunistic direct send). UI checkbox “Force Mesh”, default off. |
| `expiry` | int | Minutes. App offers 5, 10, 30, 60, 120, **1440** (24 h). |

The app does **not** locally echo the composed message into the inbox. The communicator is expected to send back a `message` (and later `messageStatus`) so the UI can show queued → mesh-accepted → confirmed.

---

## 6. Typical session

```
App                         Communicator
 |  scan name "BC-…"
 |  CONNECT + service discovery
 |  MTU 247
 |  CCCD enable on RX
 |  TX  "PIN:1234"
 |                               RX  "success\n"          (optional)
 |                               RX  {"conn":{…}}\n
 |                               RX  {"self":{…}}\n
 |                               RX  {"devices":[…]}\n
 |                               RX  {"location":[…]}\n
 |                               RX  {"neighbors":{…}}\n
 |                               RX  {"graph":{…}}\n
 |                               RX  {"traffic":{…}}\n
 |                               RX  {"id":…,"sender":…}\n   (history / live)
 |  TX  {"dm":{…}}
 |                               RX  {"message":{…queued…}}\n
 |                               RX  {"messageStatus":{…confirmed…}}\n
 |  … periodic conn / location / neighbors / traffic / graph …
```

Order after PIN is not strictly specified. Test-mode replay uses:

`conn` → `self` → `devices` → `location` → `neighbors` → `graph` → incoming DM → broadcast → location delta → traffic → neighbors refresh → partial graph → traffic → saved message → critical broadcast.

Live firmware may emit any of these at any time; the repository is upsert-oriented.

---

## 7. Message index

### Device → App (RX notify)

| Key / token | Purpose | Upsert behavior |
|-------------|---------|-----------------|
| `success` | PIN accepted (plain text) | Triggers PIN verify path |
| `conn` | Radio / stealth / GPS age of connected device | Replace latest |
| `self` | Connected device identity + own fix | Replace self; upsert roster |
| `devices` | Cluster roster | Merge by `id` |
| `location` | Position list | Merge by `id` (queue if unknown) |
| `neighbors` | Direct / indirect RF set + vitals | Replace neighbor snapshot; refresh ping log |
| `graph` | Topology edges keyed by address | Merge edges |
| `message` or top-level `sender` | Chat item | Upsert by `id\|recipient`; `deleted` removes |
| `messageStatus` | Status-only patch | Update or delete existing only |
| `traffic` | Byte / packet counters | Append time-series sample |

### App → Device (TX write)

| Token | Purpose |
|-------|---------|
| `PIN:<pin>` | Application PIN |
| `{"bc":{…}}` | Send broadcast |
| `{"dm":{…}}` | Send direct message |
| `dump` | Request full snapshot (legacy, not sent) |

---

## 8. Implementation notes for firmware or app changes

1. **Always terminate RX frames with `\n`.** Partial JSON is held in a single `StringBuilder` until the first newline, then the entire buffer is parsed and cleared. Two concatenated objects without a newline between them will fail as one parse.
2. **Do not wrap multiple message types in one object** if you want both processed. `ingest()` is a single `when` and handles one key.
3. **Graph addressing ≠ device id.** Edges use numeric `address` strings (`"1"`), roster uses ids (`"NV001"`). The app maps them via `ClusterRepository.normaliseAddress()`.
4. **`[all devices]`** is the broadcast recipient sentinel the UI already understands.
5. **Status vocabulary** should stay stable: `queued`, `delivered`, `confirmed`, `meshaccepted`, `deleted`. New values display as raw text but will not get special icons unless the UI is updated.
6. **PIN success vs feed.** Firmware can either send `success\n` or start the JSON feed immediately. Either satisfies the 5 s PIN window.
7. **TX newline.** If firmware’s BLE UART-style reader needs `\n` on writes, `sendPin()` / `sendJson()` should append it. Today they write the raw string only.
8. **Charset on outbound `msg`.** Firmware should expect the restricted Live alphabet, but inbound `text` is unrestricted after decrypt.
9. USB serial is the same application protocol; changing a JSON key for BLE also changes USB.

---

## 9. Source map

| Topic | File |
|-------|------|
| UUIDs, scan prefix, framing, PIN, TX writes | `app/src/main/java/com/blackoutcomms/live/service/BleFeedManager.kt` |
| PIN timeout, `sendJson` facade | `app/src/main/java/com/blackoutcomms/live/service/ConnectionService.kt` |
| Inbound dispatch | `app/src/main/java/com/blackoutcomms/live/data/ClusterRepository.kt` |
| Field models | `app/src/main/java/com/blackoutcomms/live/model/Models.kt` |
| Neighbors shape quirks | `app/src/main/java/com/blackoutcomms/live/data/NeighborsDeserializer.kt` |
| Outbound `bc` / `dm` | `app/src/main/java/com/blackoutcomms/live/ui/messages/SendMessageManager.kt` |
| Example payloads | `app/src/main/assets/test_data/*.json` |

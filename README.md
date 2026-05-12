# Blackout Comms Live — Android App

A graphical tracking and monitoring app for Blackout Comms mesh clusters.
Displays up to 90 devices on an offline-capable OSMDroid map with real-time
location, mesh graph, battery, relay state, and messaging.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── test_data/           ← JSON replay files (01_self, 02_devices, …)
├── java/com/blackoutcomms/live/
│   ├── data/
│   │   └── ClusterRepository.kt   ← singleton state store, JSON ingestion
│   ├── model/
│   │   └── Models.kt              ← all data classes
│   ├── service/
│   │   └── ConnectionService.kt   ← USB serial / BLE / test-mode feed
│   ├── ui/
│   │   ├── MainActivity.kt
│   │   ├── about/
│   │   │   └── AboutFragment.kt
│   │   └── map/
│   │       ├── MapFragment.kt
│   │       ├── MapViewModel.kt
│   │       ├── DeviceOverlay.kt   ← custom OSMDroid overlay
│   │       ├── DeviceDetailBottomSheet.kt
│   │       └── MessageAdapter.kt
│   └── util/
│       └── IconResolver.kt        ← maps icon strings → drawable resource IDs
└── res/
    ├── drawable/                  ← vector placeholders for all icons
    ├── layout/                    ← activity_main, fragment_map, fragment_about, …
    ├── menu/bottom_nav_menu.xml
    └── values/colors, strings, themes
```

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9+

### Build & Run

1. Clone / unzip this project.
2. Open in Android Studio (`File → Open`).
3. Sync Gradle (`File → Sync Project with Gradle Files`).
4. Run on device or emulator (`Run → Run 'app'`).

The app starts in **test mode** (`TEST_MODE = true` in `ConnectionService.kt`),
which replays `assets/test_data/*.json` in alphabetical order, simulating a
live feed.

---

## Switching to Live Hardware

1. Open `ConnectionService.kt` and set:
   ```kotlin
   const val TEST_MODE = false
   ```
2. To use **USB serial**: plug in the Blackout Comms device and tap the
   "Connect USB" option (or send `ACTION_CONNECT_USB` intent).
3. To use **BLE**: implement `connectBle()` using the Nordic BLE library stub
   already in `ConnectionService`. Call `ClusterRepository.ingest(line)` for
   each newline-delimited JSON string received.

---

## Replacing Placeholder Icons

All icons in `res/drawable/` are vector placeholders that match the icon shape
intent. To use the official Blackout Comms graphics:

1. Download each PNG from the URLs listed in the spec document (e.g.
   `https://www.offgridcomms.club/graphics/device_nonroot.png`).
2. Place each file in `res/drawable/` using the filename expected by
   `IconResolver.kt` (e.g. `device_nonroot.png`).
3. Update `IconResolver.kt` to return the PNG resource ID if the vector is to
   be replaced entirely, or use Glide for remote loading.

---

## Adding Test Data

Test data files live in `assets/test_data/`. Each file is replayed in
alphabetical order. Files may contain a single JSON object per line.

Supported payload types (auto-detected by top-level key):
- `self` — connected device identity and location
- `devices` — cluster device list
- `neighbors` — direct/indirect neighbor status
- `location` — device location updates
- `graph` — mesh relationship strengths
- `sender` / `message` — incoming/outgoing messages

---

## Architecture Notes

- **ClusterRepository** is a singleton LiveData store. All fragments observe it.
- **ConnectionService** is a foreground service that feeds raw JSON lines into
  `ClusterRepository.ingest()`.
- **DeviceOverlay** is a custom `org.osmdroid.views.overlay.Overlay` that draws
  markers, neighbour rings, and mesh graph lines directly on the `MapView`
  canvas.
- Map tiles are cached by OSMDroid in `cacheDir/osmdroid/tiles`. No internet
  is required once tiles are cached.

---

## Permissions

| Permission | Purpose |
|---|---|
| INTERNET | Download map tiles |
| USB_PERMISSION | USB serial connection |
| BLUETOOTH_SCAN / CONNECT | BLE connection |
| ACCESS_FINE_LOCATION | Required for BLE scanning on Android 10+ |

---

## License

Copyright © Blackout Comms / Chatters.io. All rights reserved.

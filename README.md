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
- For running with live hardware, you need a [Blackout Comms](https://chatters.io) communicator with BLE enabled

### Build & Run

1. Clone / unzip this project.
2. Open in Android Studio (`File → Open`).
3. Sync Gradle (`File → Sync Project with Gradle Files`).
4. Run on device or emulator (`Run → Run 'app'`).

The app can start in **test mode** (`TEST_MODE = true` in `ConnectionService.kt`),
which replays `assets/test_data/*.json` in alphabetical order, simulating a
live feed.

---

## Switching to Live Hardware

Open `ConnectionService.kt` and set:
   ```kotlin
   const val TEST_MODE = false
   ```

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

This project is licensed under the Creative Commons Attribution-NonCommercial 4.0 International License (CC BY-NC 4.0).

You are free to:
* **Share** — Copy and redistribute the material in any medium or format.
* **Adapt** — Remix, transform, and build upon the material.

Under the following terms:
* **Attribution** — You must give appropriate credit, provide a link to the license, and indicate if changes were made.
* **NonCommercial** — You may not use the material for commercial purposes.

To view a copy of this license, visit http://creativecommons.org.
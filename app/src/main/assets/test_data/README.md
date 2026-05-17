# Blackout Comms Live — Test Data

## Scenario: Desert Ops Cluster — Tonopah, Nevada

A fictional 15-device Blackout Comms cluster operating in the Nevada desert
near Tonopah, NV (approx. 38.07°N, 117.23°W — elevation ~1500m, high desert).
The cluster name is "desert-ops".

The connected device (self) is **Condor1** — a user device carried by the
operator running the app.

---

## Devices

| ID    | Name       | Nickname | Type      | Critical | Role |
|-------|------------|----------|-----------|----------|------|
| NV001 | Condor1    | (self)   | user      | —        | Connected device / app operator |
| NV002 | Condor2    | C2       | user      | no       | Mobile user, moving SW |
| NV003 | Condor3    | C3       | user      | no       | Mobile user, moving NE |
| NV004 | Eagle1     | Eagle1   | root      | **yes**  | Command device North sector |
| NV005 | Eagle2     | Eagle2   | root      | **yes**  | Command device South sector |
| NV006 | BaseAlpha  | Alpha    | node      | **yes**  | Fixed base station NW |
| NV007 | BaseBravo  | Bravo    | node      | no       | Fixed base station SE |
| NV008 | Relay-N1   | RN1      | relay     | no       | Mesh relay, central |
| NV009 | Relay-N2   | RN2      | relay     | no       | Mesh relay, central-east |
| NV010 | Relay-N3   | RN3      | relay     | no       | Mesh relay, north-central |
| NV011 | Sensor-P1  | Prox1    | proximity | no       | Proximity sensor, slow drift |
| NV012 | Sensor-P2  | Prox2    | proximity | no       | Proximity sensor, slow drift |
| NV013 | Sensor-P3  | Prox3    | proximity | no       | Proximity sensor, slow drift |
| NV014 | ThermalA   | TherA    | thermal   | no       | Thermal sensor, fixed |
| NV015 | ThermalB   | TherB    | thermal   | no       | Thermal sensor, fixed |

---

## Payload Files (replayed alphabetically)

| File | Payload | Description |
|------|---------|-------------|
| `01_self.json`           | `self`      | Condor1 at 38.0721°N, 117.2283°W, stationary, relay on, temp 28.5°C |
| `02_devices.json`        | `devices`   | Full 15-device roster with types and critical flags |
| `03_locations.json`      | `location`  | Initial positions for all 14 remote devices |
| `04_neighbors.json`      | `neighbors` | Condor1's direct neighbors (C2, RN1, Eagle1) and indirect (C3, Alpha, RN2, Prox1) |
| `05_graph.json`          | `graph`     | Full mesh relationship map — all 15 devices, bidirectional strengths |
| `06_message_incoming.json`| `message`  | Direct message from Eagle1 to Condor1 — status report |
| `07_message_broadcast.json`| `message` | Mesh broadcast from BaseAlpha to all devices — wind advisory |
| `08_location_update.json`| `location`  | Position updates for moving devices: C2, C3, Prox1 |
| `09_traffic.json`        | `traffic`   | First traffic sample: 18420 bytesIn, 9240 bytesOut, 114/38 packets |
| `10_neighbors_update.json`| `neighbors`| Refreshed neighbor data — slightly changed RSSI values |
| `11_graph_partial.json`  | `graph`     | Partial graph upsert — only Condor1's edges refreshed (tests upsert logic) |
| `12_traffic_2.json`      | `traffic`   | Second traffic sample: higher throughput — tests chart update |

---

## Geographic Layout

Devices are spread across a roughly 6km × 5km area centred on:
**38.0721°N, 117.2283°W** (Nevada desert east of Tonopah)

```
         NV006(Alpha)    NV010(RN3)   NV004(Eagle1)
              NV003(C3)      NV001(Condor1-self)  NV015(TherB)
         NV014(TherA)    NV008(RN1)   NV002(C2)
              NV012(Prox2)   NV009(RN2)   NV011(Prox1)
         NV005(Eagle2)   NV013(Prox3) NV007(Bravo)
```

---

## Signal Strength Legend (graph direct values)

- 80–100: Strong (green) — Eagle1↔Alpha, Condor1↔Condor2
- 50–79:  Good (blue)  — most relay connections  
- 30–49:  Weak (yellow) — some cross-sector links
- 6–29:   Poor (orange) — BaseBravo↔distant sensors
- 5:      Marginal (grey) — (none in this dataset)

---

## To Reset and Replay

Set `TEST_MODE = true` in `ConnectionService.kt`, run "Clear Data" from the
app menu, then switch tabs or restart to trigger a fresh replay.

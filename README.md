# Blackout Comms Live

**The Command & Intelligence App for Blackout Comms Mesh Networks**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white)](https://play.google.com/store/apps/details?id=com.blackoutcomms.live)
[![Get it on Google Play](https://img.shields.io/badge/Google_Play-Free-3DDC84?style=flat&logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.blackoutcomms.live)
[![License](https://img.shields.io/badge/License-Free-blue?style=flat)](https://chatters.io)
[![Firmware](https://img.shields.io/badge/Firmware-Blackout_Comms-C0392B?style=flat)](https://chatters.io)

Blackout Comms Live is the free Android companion app for **Blackout Comms** — a secure, encrypted, off-grid mesh communication system that operates without cell towers, internet, or any external infrastructure. Connect to any Blackout Comms communicator via Bluetooth and get a live intelligence view of your entire mesh cluster: positions, connectivity, signal strength, traffic, and broadcast messages — all in real time, all off-grid.

> **This app requires at least one Blackout Comms communicator.** See [Supported Hardware](#supported-hardware) and [chatters.io](https://chatters.io) to get started.

---

## Table of Contents

- [What Is Blackout Comms?](#what-is-blackout-comms)
- [What Makes Blackout Comms Unique](#what-makes-blackout-comms-unique)
  - [Mesh Memory](#mesh-memory)
  - [Zero-Touch Trust](#zero-touch-trust)
  - [ECDSA Message Signing](#ecdsa-message-signing)
  - [Private Clusters](#private-clusters)
  - [Frequency Hopping & Anti-Jamming](#frequency-hopping--anti-jamming)
- [About This App](#about-this-app)
- [Features](#features)
  - [Map View](#map-view)
  - [Traffic View](#traffic-view)
  - [Google Cast Support](#google-cast-support)
- [Screenshots](#screenshots)
- [Connection & Requirements](#connection--requirements)
- [Supported Hardware](#supported-hardware)
- [How the App Relates to the Firmware](#how-the-app-relates-to-the-firmware)
- [Blackout Comms vs. Meshtastic vs. MeshCore](#blackout-comms-vs-meshtastic-vs-meshcore)
- [Use Cases](#use-cases)
- [Getting Started](#getting-started)
- [Links](#links)

---

## What Is Blackout Comms?

Blackout Comms is firmware for LoRa-capable hardware devices that turns them into members of a **private, encrypted mesh communication cluster**. Once a device is flashed and onboarded to a cluster, it can:

- Send and receive encrypted text messages and group broadcasts
- Share live GPS position, heading, speed, and battery status
- Automatically establish cryptographic trust with other cluster members
- Retain and propagate the last known state of every cluster device — including ones that have powered off
- Route messages intelligently through the mesh using continuously updated RF topology data

The system is designed for conditions where infrastructure cannot be relied upon — **no cell towers, no internet, no servers, no external dependencies of any kind.** Everything runs on hardware you own.

Blackout Comms is developed and maintained by **Altware Development LLC**. Firmware, documentation, and licensing are at **[chatters.io](https://chatters.io)**.

---

## What Makes Blackout Comms Unique

Blackout Comms implements a set of capabilities that have no equivalent in any other civilian LoRa mesh communication system. These are not incremental improvements on existing systems — they are architectural differences that change what the network can do in real operational conditions.

---

### Mesh Memory

In every other civilian mesh system, when a device goes offline it disappears. The network loses all knowledge of it — no last position, no last heading, no indication of where it was when contact was lost. For real-world coordinated operations, this is a significant failure mode.

**Blackout Comms solves this with mesh memory.**

Every node in a Blackout Comms cluster continuously retains and propagates the **last known state** of every other cluster member — including devices that are currently powered off:

- Last known GPS position
- Last known heading and speed
- Last known battery level
- RF connectivity data between all device pairs
- Trust credentials (certificates and public keys) for all cluster members
- Broadcast message history

This data is distributed across every active node. It is not stored on any single device or server. When a device powers off, its last known state remains alive in the mesh — carried by every active node, available to any cluster member, including ones that join the cluster later.

**A device that boots after an extended absence receives the full cluster state immediately** — every position, every broadcast, every offline device's last known data. No briefing. No catch-up. Immediately operational.

Mesh memory is encrypted at all times. **No other civilian LoRa mesh system — including Meshtastic and MeshCore — implements mesh memory.**

Blackout Comms Live makes mesh memory visible. Offline devices remain on the map at their last known position. Tap any offline device to see its last known heading, speed, battery level, and time of last contact.

---

### Zero-Touch Trust

In most mesh systems, trust between devices must be manually configured — shared passwords, pairing procedures, or pre-loaded keys. Under real operational conditions, stopping to configure trust between devices is not always an option.

**Blackout Comms automates this entirely.**

Every cluster begins with a **root device** — the sole authority for onboarding new members. When the root onboards a device it:

1. Shares the cluster's symmetric encryption keys
2. Assigns a unique cluster identity to the device
3. Cryptographically signs that identity and issues a **root-signed certificate**

That certificate is the device's permanent proof of cluster membership. When two cluster devices meet in the field for the first time — devices that may have been deployed separately and never been within radio range of each other — they **automatically exchange and verify certificates**. Trust is established cryptographically, in the background, without any user action.

More significantly: **trust credentials are part of mesh memory.** Certificates and public keys propagate through the cluster the same way location data does. Two devices that never physically meet will have each other's credentials delivered through intermediate nodes. When they eventually come within range, trust is already established before the first direct transmission.

The result is a cluster where every authorized member automatically trusts every other authorized member — regardless of deployment sequence, regardless of geographic separation, regardless of whether they have ever been physically near each other.

---

### ECDSA Message Signing

Encryption protects message content from outsiders. But it doesn't answer a different question: **how do you know a message actually came from the device it claims to have come from?**

Every message and broadcast in a Blackout Comms cluster is **cryptographically signed** using the sending device's private key before transmission.

- Algorithm: **ECDSA** (Elliptic Curve Digital Signature Algorithm)
- Every signature is **timestamped** — preventing replay attacks
- Receiving devices verify every signature before acting on any message
- **Private keys are generated on-device at setup, stored encrypted, and never transmitted** — not to other cluster members, not to the root device

This provides:

| Property | Meaning |
|---|---|
| **Message integrity** | Content cannot be altered in transit |
| **Authentication** | Sender identity cannot be spoofed |
| **Non-repudiation** | Sending device cannot deny authorship |
| **Replay protection** | Captured messages cannot be retransmitted as new |
| **Key isolation** | Compromise of any device cannot forge messages from others |

Because private keys never leave the device that generated them, **compromise of any single device — including the root — cannot be used to forge messages from any other cluster member.** The root is an authority for identity and onboarding, but not a vulnerability for message authenticity.

No other civilian LoRa mesh firmware implements per-message ECDSA signing.

---

### Private Clusters

A Blackout Comms cluster is a **closed network**. Only devices onboarded by the root can join. No outside device can:

- Join the cluster
- Read cluster traffic
- Receive location data or broadcasts
- Enumerate cluster membership

Cluster keys, device certificates, and all encrypted traffic exist only on the hardware of authorized members. There is no account system, no cloud service, and no third party involved in any cluster operation.

**Stealth modes** reduce the RF footprint of the cluster, making it less detectable to passive RF monitoring.

The root device can issue a **remote wipe** command to any cluster member, rendering its credentials and stored cluster data unrecoverable. The target device must be within mesh range to receive the command. For situations where certainty is required and mesh range cannot be assured, generating a new cluster — new keys, new certificates, new identities — is the recommended approach to a compromised member.

---

### Frequency Hopping & Anti-Jamming

Blackout Comms transmissions **change frequency in a coordinated pattern** known only to authorized cluster members. This makes cluster communications significantly more resistant to:

- Interception by fixed-frequency monitoring
- Direction-finding by RF location equipment
- Disruption by fixed-frequency jamming

Meshtastic and MeshCore operate on fixed LoRa channels. Blackout Comms does not.

---

## About This App

Blackout Comms Live is the **command and intelligence layer** for a Blackout Comms cluster. It does not replace the communicator devices — it extends them by providing a visual, real-time operational picture of the entire cluster on a phone, tablet, or large display.

The app connects to any Blackout Comms communicator via **Bluetooth Low Energy (BLE)** and receives a continuous feed of the cluster's state through that device. Because of mesh memory, this feed includes not just the immediately visible devices but the full cluster picture — active devices, offline devices at last known position, RF topology, and broadcast message history.

**The app is free.** A Blackout Comms firmware license is required for the root device of any cluster. Member devices do not require individual licenses.

---

## Features

### Map View

The map is the primary view. It displays the live operational picture of the entire cluster.

**Active Devices**
- Real-time GPS position, updated as devices move
- Device name and identifier displayed on map marker
- Direction of travel indicated by marker orientation
- **Range indicator** — a circle around each device showing which other devices are within direct RF range vs. communicating via mesh hops
  - Devices *with* a range circle: within direct RF range of the connected communicator
  - Devices *without* a range circle: connected via mesh hops, beyond direct RF range

**Offline Devices**
- Devices that have powered off or gone out of range **remain on the map** at their last known position
- Tap any offline device to view:
  - Last known GPS coordinates
  - Last known heading
  - Last known speed
  - Last known battery level
  - Timestamp of last contact
- This data is sourced from mesh memory — it was retained and propagated by active cluster nodes after the device went offline

**Mesh Graph Overlay**
- Toggle to display live RF connection lines between every device pair in the cluster
- Line presence and appearance reflect real measured link quality propagated through mesh memory
- Instantly visualize which nodes are directly linked, which paths messages are taking, and where coverage gaps exist
- Essential for network optimization — identifying weak links, dead zones, and ideal relay placement

**Broadcast Messages**
- Incoming broadcasts from cluster members appear on the map
- Each message is **anchored to the sender's geographic location** at time of transmission
- Message content, sender identity, and timestamp displayed

**Map Controls**
- Filter by specific device or all devices
- Filter by time range — view historical positions and movement tracks
- Switch between map tile types including OpenTopoMap and standard map
- Auto-center on connected device or free pan

---

### Traffic View

The Traffic screen provides a live technical view of mesh network activity.

**Mesh Traffic Chart**
- Real-time chart of bytes and packets flowing in and out of the mesh
- Dual-axis display: packet count (left) and byte volume (right)
- Scrollable time history
- Useful for verifying cluster activity, diagnosing quiet periods, and understanding network load

**Ping Log**
- Chronological log of all device pings received by the connected communicator
- Each entry shows:
  - Device name
  - Timestamp
  - Signal strength in **dBm**
  - Connection type: **DIRECT** (within RF range) or **INDIRECT** (via mesh hops)
- Direct signal strength readings are useful for node placement optimization — find the position that gives the strongest, most consistent readings

---

### Google Cast Support

Blackout Comms Live supports **Google Cast**, enabling the live cluster map to be cast to any Chromecast-enabled display — a television, a monitor, a projector.

This enables a **central command** configuration: a coordinator at a base location casts the live cluster map to a large screen, maintaining full situational awareness of all active and offline cluster members during an operation.

Cast view is optimized for large screens — map fills the display, device labels are clearly readable, mesh graph lines are visible at scale.

---

## Screenshots

> *(Screenshots coming soon — see [chatters.io](https://www.chatters.io/using-blackout-comms-live) for current app screenshots)*

---

## Connection & Requirements

### Connecting to Your Cluster

1. Ensure your Blackout Comms communicator is powered on and the cluster is active
2. Enable Bluetooth on your Android device
3. Open Blackout Comms Live
4. Tap the Bluetooth icon in the top bar
5. Select your communicator from the device list
6. The app connects via BLE and begins receiving cluster data

The app receives the full cluster state through the connected communicator — including mesh memory data for offline devices that the communicator itself may not currently be in direct contact with.

### Requirements

| Requirement | Details |
|---|---|
| **Platform** | Android |
| **iOS** | Not currently available |
| **Bluetooth** | Bluetooth Low Energy (BLE) required |
| **Internet** | Not required — app is fully offline capable |
| **Blackout Comms device** | Required — any supported communicator |
| **Cluster license** | Required on root device — not required on member devices |
| **App cost** | Free |

---

## Supported Hardware

Blackout Comms firmware runs on the following devices, all of which are compatible with Blackout Comms Live:

| Device | Notes |
|---|---|
| **Lilygo T-Deck** | Full keyboard + touchscreen display. Preferred for messaging-heavy use. |
| **Lilygo T-Pager** | Compact pager form factor. Wearable, belt-clip friendly. |
| **Heltec Vision Master T190** | E-Paper display. Compact. |
| **Heltec v4 Exp Kit** | Fully assembled, touchscreen and node versions. Compact. |
| **Lilygo T-Beam Supreme** | Fully assembled, just print an enclosure. Compact. |
| **Lilygo T-Beam 1W** | Fully assembled, just print an enclosure. 1 watt of power. |
| **Compatible DIY LoRa builds** | See hardware guide at chatters.io |

Firmware binaries for all supported devices are available at the **[Blackout Comms firmware repository](https://github.com/altware/blackout-comms)**.

---

## How the App Relates to the Firmware

Understanding the relationship between the app and the firmware is useful for getting the most out of both.

```
┌─────────────────────────────────────────────────────────────────┐
│                      YOUR CLUSTER                               │
│                                                                 │
│  ┌──────────┐    LoRa Mesh    ┌──────────┐    LoRa Mesh        │
│  │ Device A │◄──────────────►│ Device B │◄──────────────► ...  │
│  │(T-Deck)  │                │ (Pager)  │                      │
│  └────┬─────┘                └──────────┘                      │
│       │ Bluetooth                                               │
│       │ Low Energy                                              │
│       ▼                                                         │
│  ┌──────────────────────────────────────────┐                  │
│  │         BLACKOUT COMMS LIVE              │                  │
│  │  (Android phone or tablet)               │                  │
│  │                                          │                  │
│  │  • Live map of all cluster devices       │                  │
│  │  • Offline devices at last known pos     │                  │
│  │  • Mesh graph / RF topology              │                  │
│  │  • Traffic monitoring                    │                  │
│  │  • Broadcast messages on map             │                  │
│  │  • Cast to TV via Google Cast            │                  │
│  └──────────────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
```

**The app does not communicate directly with the mesh.** It connects to one cluster device via Bluetooth and receives a feed of the cluster's state through that device. The connected device acts as a bridge — passing along the full cluster picture it has received via LoRa, including mesh memory data for devices it may not currently be in direct contact with.

**The app is a monitoring tool, not a messaging tool.** Sending messages and broadcasts is done from the communicator devices directly. The app provides the live operational overview — where everyone is, how the network is connected, what has been broadcast, and what the mesh remembers about devices that have gone dark.

**One app can monitor the entire cluster** through a single connected device. You do not need one phone per cluster device. Connect to any device in the cluster and receive the full picture.

---

## Blackout Comms vs. Meshtastic vs. MeshCore

Blackout Comms is not the only LoRa mesh system. Here is an honest comparison of where the systems differ — including where Blackout Comms does not lead.

| Capability | Blackout Comms | Meshtastic | MeshCore |
|---|:---:|:---:|:---:|
| Mesh memory | ✅ | ❌ | ❌ |
| Offline devices at last known position | ✅ | ❌ | ❌ |
| New device receives full cluster state on boot | ✅ | ❌ | ❌ |
| Root-of-trust certificate architecture | ✅ | ❌ | ❌ |
| Zero-touch trust (automatic cert exchange) | ✅ | ❌ | ❌ |
| ECDSA message signing (per-device keys) | ✅ | ❌ | ❌ |
| Replay attack protection | ✅ | ❌ | ❌ |
| Frequency hopping / anti-jamming | ✅ | ❌ | ❌ |
| Symmetric encryption of cluster traffic | ✅ | ✅ | ✅ |
| Private / closed cluster | ✅ | Partial | Partial |
| Remote wipe | ✅ | ❌ | ❌ |
| Android companion app | ✅ Free | ✅ Free | ✅ |
| iOS companion app | ❌ | ✅ | Partial |
| Google Cast support | ✅ | ❌ | ❌ |
| Offline devices shown on live map | ✅ | ❌ | ❌ |
| Broadcast messages anchored to map location | ✅ | ❌ | ❌ |
| No server / no cloud dependency | ✅ | ✅ | ✅ |
| No subscription fee | ✅ | ✅ | ✅ |
| Open source firmware | ❌ | ✅ | ✅ |
| Hardware compatibility | Select devices | 50+ devices | Select devices |
| Firmware license required (root device only) | ✅ | ❌ | ❌ |

**Where Meshtastic leads:** Meshtastic has significantly broader hardware support (50+ devices), fully open-source firmware, iOS app support, and no license requirement. It is an excellent system for open community mesh networks and situations where ecosystem breadth and open-source auditability are priorities.

**Where MeshCore leads:** MeshCore offers structured hub-and-spoke routing optimized for planned infrastructure deployments, and is open-source.

**Where Blackout Comms leads:** Private cluster operation with mesh memory, zero-touch trust, ECDSA message signing, frequency hopping, and the full BC Live monitoring capability described in this README.

The right system depends entirely on your use case.

---

## Use Cases

Blackout Comms and Blackout Comms Live are designed for scenarios where infrastructure cannot be relied upon and operational coordination matters.

**Emergency Preparedness & Grid-Down**
A family or neighborhood cluster maintains communication during a prolonged power outage, hurricane, earthquake, or civil disruption. BC Live cast to a television gives a coordinator full situational awareness of who has evacuated, who is in place, and where everyone was when they last checked in.

**Supply Run Coordination**
A coordinator at a base location monitors runners in the field via BC Live. Runners broadcast field intelligence — road conditions, open resources, route changes — which appear anchored to their map location. If a runner goes dark, their last known position, heading, and battery remain on the coordinator's map. When a fresh device powers on to assist, it immediately has the full operational picture.

**Search & Rescue Staging**
Multiple teams deployed across terrain maintain position awareness through the cluster. A team member whose device dies is not lost — their last known position remains in mesh memory. The incident coordinator's BC Live map provides a live overview of all team positions and the RF topology connecting them.

**Rural Property & Homestead**
A property spread across multiple buildings or acreage with no cell coverage uses a cluster for daily communication. BC Live on a tablet provides a live overview of who is where across the property at any time.

**Network Optimization**
During cluster setup or expansion, BC Live's mesh graph and traffic view provide real measured data for node placement decisions — signal strength, direct vs. indirect connectivity, packet delivery consistency. Position a new relay node, watch the graph update in real time, and confirm coverage before finalizing placement.

**Field Operations**
Any scenario where multiple people are operating independently across terrain and a central coordinator needs live situational awareness — without cell, without internet, without any infrastructure that could be unavailable, monitored, or compromised.

---

## Getting Started

**Step 1 — Get hardware**
Acquire a supported Blackout Comms device. See the [hardware guide](https://www.chatters.io/build) for recommended devices and suppliers.

**Step 2 — Flash firmware**
Download the firmware binary for your device from the [Blackout Comms firmware repository](https://github.com/mattcalhoun1/ChatterBuilds) and flash it using the [web flasher](https://chatterbuilds.pages.dev/ChatterBox/esp32/) or esptool. Full flashing instructions are in the firmware repository README.

**Step 3 — Create a cluster**
Follow the on-device setup to create a new cluster. This device becomes your root. Activate your root's firmware license. See how at [chatters.io](https://chatters.io/licensing).

**Step 4 — Onboard members**
Bring additional devices within range of the root to onboard them. Each receives a signed certificate and cluster keys.

**Step 5 — Install the app**
Install Blackout Comms Live from Google Play. Connect to any cluster device via Bluetooth. Your cluster appears on the map.

More helpful information: **[chatters.io/support](https://www.chatters.io/support)**

---

## Links

| Resource | URL |
|---|---|
| Website & documentation | [chatters.io](https://chatters.io) |
| Firmware repository | [github.com/altware/blackout-comms](https://github.com/mattcalhoun1/ChatterBuilds) |
| Blackout Comms Live on Google Play | [Google Play](https://play.google.com/store/apps/details?id=com.blackoutcomms.live) |
| Setup documentation | [chatters.io/docs](https://chatters.io/build) |
| Hardware guide | [chatters.io/hardware](https://chatters.io/diy) |
| Web flasher | [chatters.io/flash](https://chatters.io/flash) |
| Mesh Memory explained | [chatters.io/mesh-memory](https://chatters.io/mesh-memory) |
| Zero-Touch Trust explained | [chatters.io/zero-touch-trust](https://chatters.io/zero-touch-trust) |
| Comparison page | [chatters.io/comparison](https://www.chatters.io/mesh-comparison-blackout-comms-vs-meshtastic-vs-meshcore) |
| Firmware licensing | [chatters.io/license](https://chatters.io/licensing) |

---

## About

Blackout Comms and Blackout Comms Live are developed by **Altware Development LLC**.

For questions, support, and community discussion, visit [chatters.io](https://chatters.io).

---

*Blackout Comms. When the grid goes down — your network stays up.*
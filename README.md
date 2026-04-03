# RectiliTAK

An ATAK plugin for end-to-end encrypted, serverless mesh communication over the [Reticulum Network Stack](https://reticulum.network/).

## What is Reticulum?

Reticulum is a cryptography-based networking stack for building resilient, delay-tolerant communications over any available medium. It provides:

- **Zero-configuration networking** — devices discover each other automatically over WiFi, LoRa, serial, TCP, or any combination
- **End-to-end encryption** by default using X25519 key exchange and AES-256
- **No infrastructure required** — no servers, no SIM cards, no internet needed
- **Multi-hop mesh routing** — messages relay through intermediate nodes automatically
- **Mixed-medium transport** — a single message can traverse WiFi, hop to LoRa, then to TCP seamlessly

Reticulum was created by [Mark Qvist](https://github.com/markqvist/Reticulum) and is designed for scenarios where traditional networks are unavailable, unreliable, or untrusted.

## About RectiliTAK

RectiliTAK brings Reticulum mesh networking directly into ATAK (Android Team Awareness Kit), giving tactical operators encrypted messaging, group chat, contact management, and location sharing — all without any network infrastructure.

The plugin embeds a full Python 3.11 runtime with the Reticulum library inside the APK using [Chaquopy](https://chaquo.com/chaquopy/), communicating with the ATAK Java/Kotlin layer over a localhost TCP bridge.

## Features

### Main Dashboard
- Quick-access buttons for Messenger and Location settings
- RNS address display (tap to copy) for sharing with peers
- Connection status indicator

### Direct Messaging
- End-to-end encrypted messages to any Reticulum address
- Paste an address or pick from saved contacts
- Contact name resolution — address bar shows contact names instead of hex
- Path discovery with status feedback

### Contacts
- Save peers by name and RNS address
- Quick-select contacts for messaging
- Contact names displayed in message history and address bar

### Group Chat
- Create named groups from saved contacts
- Messages sent as individual DMs to each group member
- Group messages tagged and displayed with group name
- Long-press to view members or delete a group

### Location Sharing (CoT)
- Share your ATAK position as a Cursor on Target (CoT) event over RNS
- Received locations appear as markers on the ATAK map
- **Auto-share** with configurable frequency (30s to 10 min)
- **Share with** a specific contact, a group, or all contacts
- **Map icon selector** with visual previews — choose how you appear on others' maps:
  - **MIL-STD-2525**: Friendly Ground Unit, Vehicle, Infantry, Air, Sea, Neutral, Unknown
  - **APRS icons** (23 bundled): Car, Jeep, Truck, Van, Motorcycle, Bicycle, Bus, Semi Truck, RV, Helicopter, Small/Large Aircraft, Power Boat, Sailboat, Jogger, Emergency, Ambulance, Fire Truck, Fire, Police, Hospital, Aid Station, House
- Manual one-time share button

### Reticulum Bridge
- Embedded Python 3.11 runtime via Chaquopy — no external apps needed
- Full Reticulum Network Stack running inside the plugin
- Localhost TCP/JSON bridge (port 17000) for Java-Python IPC
- Auto-reconnect with supervised bridge lifecycle
- Lazy initialization — Python loads on first use, not at ATAK startup
- Compatible with Sideband (AutoInterface disabled by default to avoid port conflicts)

### Peer Discovery
- Automatic peer discovery via Reticulum announce packets
- Peer discovery notifications in the chat panel
- Persistent peer list across app restarts

### Identity Management
- Persistent RNS identity (X25519/Ed25519 keypair) stored in app private files
- Callsign pulled from ATAK's MapView device callsign
- UID sourced from ATAK's self marker

### Settings
- **Chat Appearance** — font size (Small/Medium/Large/XL), timestamps, compact mode
- **Reticulum Network** — bridge toggle, transport relay mode, WiFi/LAN auto-discovery
- **TCP Bootstrap** — configurable host/port (default: rns.beleth.net:4242)

## Architecture

```
ATAK Plugin APK (self-contained)
|
+-- Java/Kotlin layer
|   +-- RectiliTAKLifecycle.java      Plugin entry point
|   +-- RectiliTAKMapComponent.java   MapComponent lifecycle
|   +-- MainPanelDropDown.kt          Main dashboard with navigation
|   +-- ChatPanelDropDown.kt          Messenger UI
|   +-- RNSBridgeService.kt           Bridge manager (singleton)
|   +-- SocketClient.kt               TCP client for bridge IPC
|   +-- IdentityManager.kt            Callsign/UID/peer storage
|   +-- ContactManager.kt             Contact name/address storage
|   +-- GroupManager.kt               Group chat management
|   +-- CotHelper.kt                  CoT location read/inject
|   +-- ChaquopyContextWrapper.java   ATAK-Chaquopy context bridge
|
+-- Python layer (bundled via Chaquopy)
|   +-- rns_bridge.py                 Reticulum chat bridge
|   +-- RNS library                   Installed via pip at build time
|
+-- res/
    +-- layout/main_panel.xml         Main dashboard layout
    +-- layout/chat_panel.xml         Messenger layout
    +-- layout/location_panel.xml     Location settings layout
    +-- raw/rns_default_config        Default Reticulum config
    +-- xml/preferences.xml           Plugin settings
```

**Data flow:**
```
User sends message / shares location
      |
MainPanelDropDown -> ChatPanelDropDown (Kotlin)
      |  JSON over localhost:17000
RNSBridgeService -> SocketClient
      |
rns_bridge.py (Python)
      |
Reticulum Network Stack
      |  (WiFi / LoRa / TCP)
Remote peer's rns_bridge.py
      |
Remote ATAK map / chat panel
```

## Building

### Prerequisites
- Android SDK (compileSdk 36)
- ATAK CIV 5.6.0 SDK (`main.jar` in `app/libs/`)
- `atak-gradle-takdev.jar` — path set in `local.properties`
- Gradle 8.14.x (wrapper included)
- Java 8+ (JVM target 1.8)

### Setup

1. Clone the repo
2. Create `local.properties` in the project root:
   ```
   sdk.dir=/path/to/Android/sdk
   takdev.plugin=/path/to/ATAK-CIV-SDK/atak-gradle-takdev.jar
   ```
3. Copy the ATAK SDK keystore to `app/build/android_keystore`
4. Build:
   ```
   ./gradlew :app:assembleCivDebug
   ```
5. Install:
   ```
   adb install app/build/outputs/apk/civ/debug/ATAK-Plugin-RectiliTAK-*.apk
   ```

### Build Flavors
- `civ` (default) — ATAK Civilian
- `mil` — ATAK Military
- `gov` — ATAK Government

## Testing

A test peer script is included for local testing:

```bash
pip install rns
python test_peer.py
```

This starts a Reticulum peer on your computer that echoes back any direct messages. Copy the displayed address into RectiliTAK's Direct mode to test connectivity.

## Transports

| Transport | Status | Description |
|-----------|--------|-------------|
| WiFi/LAN (AutoInterface) | Working | Zero-config discovery on local network |
| TCP Bootstrap | Working | Connect to remote Reticulum node (default: rns.beleth.net:4242) |
| LoRa via RNode USB OTG | Planned | Long-range mesh via RNode hardware |

## Compatibility

- **ATAK:** 5.6.0 CIV/MIL/GOV
- **Android:** API 24+ (Android 7.0)
- **Python:** 3.11 (bundled)
- **Reticulum:** Latest via pip

## Known Issues

- **Sideband conflict** — If the Sideband app is running, disable WiFi/LAN Auto-Discovery in RectiliTAK settings to avoid port conflicts. TCP bootstrap works independently.
- **GPS required for location sharing** — Device must have a GPS fix before sharing location. Check that your blue marker is visible on the ATAK map.

## License

See [TakTemplate/app/libs/license.txt](TakTemplate/app/libs/license.txt) for ATAK SDK license terms.

## References

- [Reticulum Network Stack](https://reticulum.network/)
- [Reticulum GitHub](https://github.com/markqvist/Reticulum)
- [Chaquopy — Python for Android](https://chaquo.com/chaquopy/)
- [ATAK CIV GitHub](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV)

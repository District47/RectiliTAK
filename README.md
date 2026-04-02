# RectiliTAK

An ATAK plugin for end-to-end encrypted, serverless mesh chat over the [Reticulum Network Stack](https://reticulum.network/).

## What is Reticulum?

Reticulum is a cryptography-based networking stack for building resilient, delay-tolerant communications over any available medium. It provides:

- **Zero-configuration networking** — devices discover each other automatically over WiFi, LoRa, serial, TCP, or any combination
- **End-to-end encryption** by default using X25519 key exchange and AES-256
- **No infrastructure required** — no servers, no SIM cards, no internet needed
- **Multi-hop mesh routing** — messages relay through intermediate nodes automatically
- **Mixed-medium transport** — a single message can traverse WiFi, hop to LoRa, then to TCP seamlessly

Reticulum was created by [Mark Qvist](https://github.com/markqvist/Reticulum) and is designed for scenarios where traditional networks are unavailable, unreliable, or untrusted.

## About RectiliTAK

RectiliTAK brings Reticulum mesh chat directly into ATAK (Android Team Awareness Kit), giving tactical operators a decentralized, encrypted messaging capability that works without any network infrastructure.

The plugin embeds a full Python 3.11 runtime with the Reticulum library inside the APK using [Chaquopy](https://chaquo.com/chaquopy/), communicating with the ATAK Java/Kotlin layer over a localhost TCP bridge.

## Features

### Mesh Chat
- Room-based messaging with **All Chat**, **Team**, and **Command** channels
- Room selector dropdown for switching between channels
- Local echo for sent messages with real-time delivery
- Dark-themed UI matching ATAK's visual style

### Reticulum Bridge
- Embedded Python 3.11 runtime via Chaquopy — no external apps needed
- Full Reticulum Network Stack running inside the plugin
- Localhost TCP/JSON bridge (port 17000) for Java-Python IPC
- Auto-reconnect with supervised bridge lifecycle

### Peer Discovery
- Automatic peer discovery via Reticulum announce packets
- Peer join/leave notifications in the chat panel
- Persistent peer list across app restarts (SharedPreferences-backed)

### Identity Management
- Persistent RNS identity (X25519/Ed25519 keypair) stored in app private files
- Callsign pulled from ATAK's MapView device callsign
- UID sourced from ATAK's self marker

### Configuration
- ATAK-integrated preferences panel (Settings > Tool Preferences > RectiliTAK)
- Reticulum transport relay toggle for multi-hop routing
- TCP bootstrap peer configuration (host/port) for internet connectivity
- Default config uses WiFi/LAN auto-discovery (AutoInterface)
- Bridge restart on config changes

## Architecture

```
ATAK Plugin APK (self-contained)
|
+-- Java/Kotlin layer
|   +-- RectiliTAKLifecycle.java      Plugin entry point
|   +-- RectiliTAKMapComponent.java   MapComponent lifecycle
|   +-- ChatPanelDropDown.kt          Chat UI (DropDownReceiver)
|   +-- RNSBridgeService.kt           Bridge manager (singleton)
|   +-- SocketClient.kt               TCP client for bridge IPC
|   +-- IdentityManager.kt            Callsign/UID/peer storage
|   +-- ChaquopyContextWrapper.java   ATAK-Chaquopy context bridge
|
+-- Python layer (bundled via Chaquopy)
|   +-- rns_bridge.py                 Reticulum chat bridge
|   +-- RNS library                   Installed via pip at build time
|
+-- res/
    +-- layout/chat_panel.xml         Chat panel layout
    +-- raw/rns_default_config        Default Reticulum config
    +-- xml/preferences.xml           Settings UI
```

**Data flow:**
```
User types message
      |
ChatPanelDropDown (Kotlin)
      |  JSON over localhost:17000
RNSBridgeService -> SocketClient
      |
rns_bridge.py (Python)
      |
Reticulum Network Stack
      |  (WiFi / LoRa / TCP)
Remote peer
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

## Transports

| Transport | Status | Description |
|-----------|--------|-------------|
| WiFi/LAN (AutoInterface) | Working | Zero-config discovery on local network |
| TCP Bootstrap | Configurable | Connect to remote Reticulum node via internet |
| LoRa via RNode USB OTG | Planned | Long-range mesh via RNode hardware |

## Compatibility

- **ATAK:** 5.6.0 CIV/MIL/GOV
- **Android:** API 24+ (Android 7.0)
- **Python:** 3.11 (bundled)
- **Reticulum:** Latest via pip

## License

See [TakTemplate/app/libs/license.txt](TakTemplate/app/libs/license.txt) for ATAK SDK license terms.

## References

- [Reticulum Network Stack](https://reticulum.network/)
- [Reticulum GitHub](https://github.com/markqvist/Reticulum)
- [Chaquopy — Python for Android](https://chaquo.com/chaquopy/)
- [ATAK CIV GitHub](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV)

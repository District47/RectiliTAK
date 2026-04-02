# ATAK Reticulum Chat Plugin
## Project Reference Document

**Project:** `atak-rns-chat`  
**Stack:** ATAK Plugin (Java/Kotlin) + Python RNS bridge via Chaquopy  
**Goal:** Self-contained ATAK plugin providing end-to-end encrypted, serverless chat over the Reticulum Network Stack

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Project Folder Structure](#project-folder-structure)
3. [Gradle / Build Setup](#gradle--build-setup)
4. [Bridge Protocol](#bridge-protocol)
5. [Python Bridge — rns_bridge.py](#python-bridge--rns_bridgepy)
6. [Android Service — RNSBridgeService.kt](#android-service--rnsbridgeservicekt)
7. [ATAK Plugin Entry Point](#atak-plugin-entry-point)
8. [Chat UI Panel](#chat-ui-panel)
9. [Identity & Peer Management](#identity--peer-management)
10. [Reticulum Interface Configuration](#reticulum-interface-configuration)
11. [Key Design Decisions](#key-design-decisions)
12. [Difficulty Map](#difficulty-map)
13. [Next Steps / Milestones](#next-steps--milestones)

---

## Architecture Overview

```
ATAK Plugin APK (self-contained)
│
├── Java/Kotlin layer
│   ├── ATAKChatPlugin.kt          ← Plugin lifecycle entry point
│   ├── ChatPanelDropDown.kt       ← ATAK MapComponent chat UI
│   ├── RNSBridgeService.kt        ← Android Service, supervises Python
│   ├── SocketClient.kt            ← Localhost TCP client (port 17000)
│   └── IdentityManager.kt         ← Callsign ↔ RNS hash lookup table
│
├── Python layer (bundled via Chaquopy)
│   ├── rns_bridge.py              ← Main bridge process
│   └── RNS library                ← Installed via pip at build time
│
└── res/
    ├── layout/chat_panel.xml
    └── raw/rns_default_config     ← Default Reticulum interface config
```

**Data flow:**

```
User types message
      ↓
ChatPanelDropDown (Java)
      ↓  JSON over localhost:17000
RNSBridgeService → SocketClient
      ↓
rns_bridge.py
      ↓
Reticulum Network Stack
      ↓  (WiFi / LoRa RNode / TCP)
Remote peer rns_bridge.py
      ↓  JSON event
Remote ATAK chat panel
```

---

## Project Folder Structure

```
atak-rns-chat/
├── app/
│   ├── build.gradle
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/yourorg/atakrnschat/
│   │       │   ├── ATAKChatPlugin.kt
│   │       │   ├── ChatPanelDropDown.kt
│   │       │   ├── RNSBridgeService.kt
│   │       │   ├── SocketClient.kt
│   │       │   └── IdentityManager.kt
│   │       ├── python/
│   │       │   └── rns_bridge.py
│   │       └── res/
│   │           ├── layout/
│   │           │   └── chat_panel.xml
│   │           └── raw/
│   │               └── rns_default_config
├── build.gradle        ← root
└── settings.gradle
```

---

## Gradle / Build Setup

### Root `build.gradle`

```gradle
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url "https://chaquo.com/maven" }
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
        classpath 'com.chaquo.python:gradle:15.0.1'
    }
}
```

### Module `app/build.gradle`

```gradle
plugins {
    id 'com.android.library'
    id 'com.chaquo.python'
    id 'kotlin-android'
}

android {
    compileSdk 33
    defaultConfig {
        minSdk 21
        targetSdk 33

        // Chaquopy Python config
        python {
            version "3.11"
            pip {
                install "rns"           // Reticulum Network Stack
                install "cryptography"
            }
            // Target ABIs for deployment
            buildPython "/usr/bin/python3"
        }

        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a", "x86_64"
        }
    }
}

dependencies {
    // ATAK SDK — point at your local SDK path
    compileOnly fileTree(dir: '../atak-sdk', include: ['*.jar'])
    implementation 'androidx.core:core-ktx:1.10.1'
}
```

> **Note:** Chaquopy bundles the Python runtime and all pip packages directly
> into the APK. No external Python installation is needed on the device.

---

## Bridge Protocol

All communication between the Java layer and the Python bridge uses
**newline-delimited JSON** over a localhost TCP socket on port **17000**.

### Java → Python (commands)

```json
// Send a message to a room
{ "cmd": "send", "room": "All Chat", "body": "Hello net", "from": "CALLSIGN", "uid": "UID-1234" }

// Set identity on startup
{ "cmd": "set_identity", "callsign": "CALLSIGN", "uid": "UID-1234" }

// Request current peer list
{ "cmd": "get_peers" }
```

### Python → Java (events)

```json
// Bridge is ready
{ "event": "ready" }

// Incoming message
{ "event": "message", "room": "All Chat", "from": "DELTA1", "body": "Copy that", "ts": 1712000000 }

// New peer discovered via announce
{ "event": "peer_appeared", "callsign": "DELTA1", "hash": "a3f1c2..." }

// Peer has not been heard for timeout period
{ "event": "peer_lost", "callsign": "DELTA1" }

// Response to get_peers
{ "event": "peers", "peers": { "a3f1c2...": "DELTA1", "b7e3a1...": "BRAVO2" } }
```

---

## Python Bridge — rns_bridge.py

```python
#!/usr/bin/env python3
"""
rns_bridge.py
ATAK Reticulum Chat Bridge

Runs as a subprocess managed by RNSBridgeService.
Exposes a localhost TCP server on port 17000.
Accepts JSON commands from the Java layer.
Emits JSON events to all connected Java clients.
"""

import RNS
import json
import socket
import threading
import time
import argparse
import os

APP_NAME    = "atak_chat"
BRIDGE_PORT = 17000
IDENTITY_FILE = "atak_identity"
ANNOUNCE_INTERVAL = 300  # seconds

ROOMS = ["All Chat", "Team", "Command"]


class ATAKChatBridge:

    def __init__(self, callsign: str, uid: str, config_path: str = None):
        self.callsign = callsign
        self.uid      = uid
        self.peers    = {}   # hash -> callsign
        self.clients  = []   # connected Java sockets (thread-safe append/remove)
        self._lock    = threading.Lock()

        # Initialise Reticulum (uses default config or supplied path)
        self.reticulum = RNS.Reticulum(config_path)

        # Load or create persistent identity
        id_path = os.path.join(
            RNS.Reticulum.configdir, IDENTITY_FILE
        )
        if os.path.exists(id_path):
            self.identity = RNS.Identity.from_file(id_path)
            RNS.log("Loaded existing RNS identity")
        else:
            self.identity = RNS.Identity()
            self.identity.to_file(id_path)
            RNS.log("Created new RNS identity")

        # Set up one inbound destination per chat room
        self.room_destinations = {}
        for room in ROOMS:
            self._setup_room(room)

        RNS.log(f"Bridge ready — callsign={callsign}, uid={uid}")

    # ------------------------------------------------------------------
    # Room setup
    # ------------------------------------------------------------------

    def _room_aspect(self, room_name: str) -> str:
        """Deterministic aspect string from room name."""
        return room_name.lower().replace(" ", "_")

    def _setup_room(self, room_name: str):
        dest = RNS.Destination(
            self.identity,
            RNS.Destination.IN,
            RNS.Destination.PLAIN,   # broadcast; readable by all
            APP_NAME,
            self._room_aspect(room_name)
        )
        dest.set_packet_callback(self._make_packet_callback(room_name))
        self.room_destinations[room_name] = dest
        RNS.log(f"Room '{room_name}' destination: "
                f"{RNS.prettyhexrep(dest.hash)}")

    def _make_packet_callback(self, room_name):
        def callback(message, packet):
            self._on_packet(message, packet, room_name)
        return callback

    # ------------------------------------------------------------------
    # Packet receive
    # ------------------------------------------------------------------

    def _on_packet(self, message: bytes, packet, room_name: str):
        try:
            data = json.loads(message.decode("utf-8"))
            sender_hash = RNS.prettyhexrep(
                packet.generating_destination.hash
            )
            callsign = data.get("from", "Unknown")

            # Register new peer
            if sender_hash not in self.peers:
                self.peers[sender_hash] = callsign
                self._emit({
                    "event":    "peer_appeared",
                    "callsign": callsign,
                    "hash":     sender_hash
                })

            self._emit({
                "event": "message",
                "room":  room_name,
                "from":  callsign,
                "body":  data.get("body", ""),
                "ts":    data.get("ts", int(time.time()))
            })

        except Exception as e:
            RNS.log(f"Packet parse error: {e}", RNS.LOG_ERROR)

    # ------------------------------------------------------------------
    # Send
    # ------------------------------------------------------------------

    def send(self, room: str, body: str):
        if room not in self.room_destinations:
            RNS.log(f"Unknown room: {room}", RNS.LOG_WARNING)
            return
        payload = json.dumps({
            "from": self.callsign,
            "uid":  self.uid,
            "room": room,
            "body": body,
            "ts":   int(time.time())
        }).encode("utf-8")
        packet = RNS.Packet(self.room_destinations[room], payload)
        packet.send()

    # ------------------------------------------------------------------
    # Announce
    # ------------------------------------------------------------------

    def _announce(self):
        app_data = json.dumps({
            "callsign": self.callsign,
            "uid":      self.uid
        }).encode("utf-8")
        for dest in self.room_destinations.values():
            dest.announce(app_data=app_data)
        RNS.log("Announced on all room destinations")

    def _announce_loop(self):
        while True:
            time.sleep(ANNOUNCE_INTERVAL)
            self._announce()

    # ------------------------------------------------------------------
    # Emit to Java clients
    # ------------------------------------------------------------------

    def _emit(self, event: dict):
        line = json.dumps(event) + "\n"
        encoded = line.encode("utf-8")
        with self._lock:
            dead = []
            for client in self.clients:
                try:
                    client.sendall(encoded)
                except Exception:
                    dead.append(client)
            for d in dead:
                self.clients.remove(d)

    # ------------------------------------------------------------------
    # Bridge TCP server
    # ------------------------------------------------------------------

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", BRIDGE_PORT))
        srv.listen(5)
        RNS.log(f"Bridge TCP server listening on port {BRIDGE_PORT}")

        # Initial announce
        self._announce()

        # Periodic re-announce
        threading.Thread(
            target=self._announce_loop, daemon=True
        ).start()

        # Signal ready to any already-waiting Java client
        self._emit({"event": "ready"})

        while True:
            conn, addr = srv.accept()
            RNS.log(f"Java client connected from {addr}")
            with self._lock:
                self.clients.append(conn)
            threading.Thread(
                target=self._handle_client,
                args=(conn,),
                daemon=True
            ).start()

    def _handle_client(self, conn: socket.socket):
        buf = ""
        try:
            while True:
                chunk = conn.recv(4096).decode("utf-8")
                if not chunk:
                    break
                buf += chunk
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if line:
                        try:
                            self._handle_command(json.loads(line))
                        except json.JSONDecodeError as e:
                            RNS.log(f"Bad JSON from client: {e}",
                                    RNS.LOG_WARNING)
        except Exception as e:
            RNS.log(f"Client handler error: {e}", RNS.LOG_WARNING)
        finally:
            with self._lock:
                if conn in self.clients:
                    self.clients.remove(conn)

    def _handle_command(self, cmd: dict):
        c = cmd.get("cmd")
        if c == "send":
            self.send(cmd.get("room", "All Chat"), cmd.get("body", ""))
        elif c == "set_identity":
            self.callsign = cmd.get("callsign", self.callsign)
            self.uid      = cmd.get("uid", self.uid)
            self._announce()
        elif c == "get_peers":
            self._emit({"event": "peers", "peers": self.peers})
        else:
            RNS.log(f"Unknown command: {c}", RNS.LOG_WARNING)


# ------------------------------------------------------------------
# Entry point
# ------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--callsign", default="UNKNOWN")
    parser.add_argument("--uid",      default="UID-0000")
    parser.add_argument("--config",   default=None)
    args = parser.parse_args()

    bridge = ATAKChatBridge(
        callsign=args.callsign,
        uid=args.uid,
        config_path=args.config
    )
    bridge.run()
```

---

## Android Service — RNSBridgeService.kt

```kotlin
package com.yourorg.atakrnschat

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Socket

/**
 * RNSBridgeService
 *
 * Manages the Python RNS bridge subprocess lifecycle.
 * Exposes sendMessage() for the chat UI.
 * Delivers inbound JSON events to registered BridgeEventListeners.
 */
class RNSBridgeService : Service() {

    companion object {
        private const val TAG       = "RNSBridgeService"
        private const val PORT      = 17000
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private var socket: Socket?      = null
    private var writer: PrintWriter? = null
    private val listeners            = mutableListOf<BridgeEventListener>()
    private var running              = false

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startPythonBridge()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        socket?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // Python bridge startup
    // ------------------------------------------------------------------

    private fun startPythonBridge() {
        Thread {
            // Initialise Chaquopy once
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }

            val py       = Python.getInstance()
            val callsign = getCallsign()
            val uid      = getUID()

            // Run the bridge script — this blocks until the script exits
            try {
                val module = py.getModule("rns_bridge")
                // Pass args by setting sys.argv before calling main
                val sys = py.getModule("sys")
                sys["argv"] = listOf(
                    "rns_bridge.py",
                    "--callsign", callsign,
                    "--uid",      uid
                )
                module.callAttr("main")   // calls if __name__ == '__main__' block
            } catch (e: Exception) {
                Log.e(TAG, "Python bridge crashed: ${e.message}")
            }

            // If bridge exits, reconnect after delay
            if (running) {
                Thread.sleep(RECONNECT_DELAY_MS)
                startPythonBridge()
            }
        }.start()

        // Give Python time to bind the socket, then connect
        Thread {
            Thread.sleep(2_500)
            connectSocket()
        }.start()
    }

    // ------------------------------------------------------------------
    // Socket connection
    // ------------------------------------------------------------------

    private fun connectSocket() {
        while (running) {
            try {
                Log.d(TAG, "Connecting to bridge on port $PORT...")
                socket = Socket("127.0.0.1", PORT)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                Log.d(TAG, "Connected to bridge")

                // Send identity
                sendCommand(JSONObject().apply {
                    put("cmd",      "set_identity")
                    put("callsign", getCallsign())
                    put("uid",      getUID())
                })

                // Read loop
                val reader = socket!!.getInputStream().bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { handleEvent(JSONObject(it)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Socket error: ${e.message}, retrying...")
            }
            if (running) Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------
    // Commands → Python
    // ------------------------------------------------------------------

    fun sendMessage(room: String, body: String) {
        sendCommand(JSONObject().apply {
            put("cmd",  "send")
            put("room", room)
            put("body", body)
        })
    }

    fun requestPeers() {
        sendCommand(JSONObject().apply { put("cmd", "get_peers") })
    }

    private fun sendCommand(cmd: JSONObject) {
        Thread {
            try {
                writer?.println(cmd.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Events ← Python
    // ------------------------------------------------------------------

    private fun handleEvent(event: JSONObject) {
        listeners.forEach { it.onEvent(event) }
    }

    fun addListener(l: BridgeEventListener)    { listeners.add(l) }
    fun removeListener(l: BridgeEventListener) { listeners.remove(l) }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun getCallsign(): String {
        // Pull callsign from ATAK preferences
        val prefs = getSharedPreferences("atak_rns", MODE_PRIVATE)
        return prefs.getString("callsign", "UNKNOWN") ?: "UNKNOWN"
    }

    private fun getUID(): String {
        val prefs = getSharedPreferences("atak_rns", MODE_PRIVATE)
        return prefs.getString("uid", java.util.UUID.randomUUID().toString())
            ?: "UID-0000"
    }
}

// ------------------------------------------------------------------
// Event listener interface
// ------------------------------------------------------------------

interface BridgeEventListener {
    fun onEvent(event: JSONObject)
}
```

---

## ATAK Plugin Entry Point

```kotlin
package com.yourorg.atakrnschat

import android.content.Context
import android.content.Intent
import com.atakmap.android.maps.MapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.android.ipc.AtakBroadcast

class ATAKChatPlugin(
    private val pluginContext: Context,
    private val mapView: MapView
) : MapComponent {

    private lateinit var bridgeServiceIntent: Intent
    private lateinit var chatPanel: ChatPanelDropDown

    override fun onCreate(context: Context, intent: Intent, mapView: MapView) {
        // Start bridge service
        bridgeServiceIntent = Intent(pluginContext, RNSBridgeService::class.java)
        pluginContext.startService(bridgeServiceIntent)

        // Register chat panel drop-down
        chatPanel = ChatPanelDropDown(mapView, pluginContext)
        chatPanel.register()
    }

    override fun onDestroy(context: Context, mapView: MapView) {
        pluginContext.stopService(bridgeServiceIntent)
        chatPanel.dispose()
    }

    // Required MapComponent stubs
    override fun onStart(context: Context, mapView: MapView)  {}
    override fun onStop(context: Context, mapView: MapView)   {}
    override fun onPause(context: Context, mapView: MapView)  {}
    override fun onResume(context: Context, mapView: MapView) {}
}
```

---

## Chat UI Panel

```kotlin
package com.yourorg.atakrnschat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.atakmap.android.dropdown.DropDown
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.maps.MapView
import org.json.JSONObject

class ChatPanelDropDown(
    mapView: MapView,
    private val pluginContext: Context
) : DropDownReceiver(mapView), BridgeEventListener {

    companion object {
        const val SHOW_CHAT = "com.yourorg.atakrnschat.SHOW_CHAT"
    }

    private val rootView: View
    private val messageList: ListView
    private val messageInput: EditText
    private val sendButton: Button
    private val roomSpinner: Spinner
    private val messages = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private val rooms = listOf("All Chat", "Team", "Command")

    init {
        val inflater = LayoutInflater.from(pluginContext)
        rootView = inflater.inflate(R.layout.chat_panel, null)

        messageList  = rootView.findViewById(R.id.messageList)
        messageInput = rootView.findViewById(R.id.messageInput)
        sendButton   = rootView.findViewById(R.id.sendButton)
        roomSpinner  = rootView.findViewById(R.id.roomSpinner)

        adapter = ArrayAdapter(pluginContext,
            android.R.layout.simple_list_item_1, messages)
        messageList.adapter = adapter

        roomSpinner.adapter = ArrayAdapter(pluginContext,
            android.R.layout.simple_spinner_item, rooms)

        sendButton.setOnClickListener { sendMessage() }
    }

    private fun sendMessage() {
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return
        val room = roomSpinner.selectedItem as String

        // Display locally immediately
        appendMessage("[You] $body")
        messageInput.setText("")

        // Send via bridge service
        getBridgeService()?.sendMessage(room, body)
    }

    // BridgeEventListener
    override fun onEvent(event: JSONObject) {
        when (event.getString("event")) {
            "message" -> {
                val from = event.getString("from")
                val body = event.getString("body")
                val room = event.getString("room")
                mapView.post { appendMessage("[$room] $from: $body") }
            }
            "peer_appeared" -> {
                val cs = event.getString("callsign")
                mapView.post { appendMessage("*** $cs joined the net") }
            }
            "ready" -> {
                mapView.post { appendMessage("*** RNS bridge ready") }
            }
        }
    }

    private fun appendMessage(msg: String) {
        messages.add(msg)
        adapter.notifyDataSetChanged()
        messageList.smoothScrollToPosition(messages.size - 1)
    }

    private fun getBridgeService(): RNSBridgeService? {
        // In a real implementation, bind to the service via ServiceConnection
        return null // placeholder
    }

    override fun disposeImpl() {}

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SHOW_CHAT) {
            showDropDown(rootView,
                DropDown.HALF_WIDTH, DropDown.FULL_HEIGHT,
                DropDown.HALF_WIDTH, DropDown.FULL_HEIGHT)
        }
    }
}
```

### `res/layout/chat_panel.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#1a1a1a"
    android:padding="8dp">

    <Spinner
        android:id="@+id/roomSpinner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#2a2a2a"
        android:textColor="#00ff88" />

    <ListView
        android:id="@+id/messageList"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:divider="#2a2a2a"
        android:dividerHeight="1dp"
        android:background="#1a1a1a" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="4dp">

        <EditText
            android:id="@+id/messageInput"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="Message..."
            android:textColor="#ffffff"
            android:textColorHint="#666666"
            android:background="#2a2a2a"
            android:padding="8dp"
            android:inputType="text" />

        <Button
            android:id="@+id/sendButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Send"
            android:textColor="#000000"
            android:backgroundTint="#00ff88"
            android:layout_marginStart="4dp" />

    </LinearLayout>
</LinearLayout>
```

---

## Identity & Peer Management

Each device gets a persistent RNS Identity (X25519/Ed25519 keypair) stored in
the app's private files directory. The identity persists across app restarts.

**Callsign ↔ RNS hash mapping** is built automatically from announce packets:

```
Announce payload (app_data):
  { "callsign": "DELTA1", "uid": "ATAK-UID-XXXX" }

Stored in peers dict:
  { "a3f1c2...": "DELTA1" }
```

No server, no directory service — peers discover each other organically as
announce packets propagate through the Reticulum mesh.

---

## Reticulum Interface Configuration

The plugin ships a default Reticulum config (in `res/raw/rns_default_config`)
that is written to the app's private config directory on first launch.

### Default config (WiFi/LAN auto-discovery only)

```
[reticulum]
enable_transport = false
share_instance   = false

[interfaces]

  [[Default Interface]]
  type    = AutoInterface
  enabled = true
```

### Adding a TCP bootstrap peer (internet reachable node)

```
  [[RNS TCP Bootstrap]]
  type             = TCPClientInterface
  enabled          = true
  target_host      = amsterdam.connect.reticulum.network
  target_port      = 4965
```

### Adding a LoRa RNode via USB OTG

```
  [[RNode LoRa]]
  type            = RNodeInterface
  enabled         = true
  port            = /dev/ttyUSB0
  frequency       = 915000000
  bandwidth       = 125000
  txpower         = 17
  spreadingfactor = 8
  codingrate      = 5
```

> A settings panel in the plugin should allow the user to edit this config
> and restart the bridge service to apply changes.

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Python packaging | Chaquopy | Single APK, no companion app |
| Java↔Python IPC | Localhost TCP JSON | Simple, debuggable, language-agnostic |
| Encryption | RNS native (X25519 + AEAD) | No additional crypto code needed |
| Group channel model | Deterministic destination hash per room name | No server coordination needed |
| Peer discovery | RNS announce with callsign in app_data | Decentralised, zero config |
| Identity persistence | RNS Identity file in app private dir | Survives app restart |
| Message format | Minimal JSON envelope | Easy to extend |

---

## Difficulty Map

| Component | Difficulty |
|---|---|
| rns_bridge.py (Python) | Low — RNS API is clean |
| Chaquopy build setup | Medium — first time setup |
| RNSBridgeService (Android) | Medium |
| ServiceConnection wiring | Medium |
| ChatPanelDropDown (ATAK UI) | Medium — ATAK SDK familiarity needed |
| Callsign↔hash mapping | Low |
| Default config management | Low |
| LoRa RNode over USB OTG | Medium-Hard (Android USB serial permissions) |
| Settings panel for RNS config | Medium |

---

## Next Steps / Milestones

**Milestone 1 — Bridge proof of concept**
- [ ] Scaffold Android project with Chaquopy
- [ ] Confirm `import RNS` works inside Chaquopy at runtime
- [ ] Run rns_bridge.py, verify TCP server starts
- [ ] Java socket client sends a command, receives an event

**Milestone 2 — Basic chat**
- [ ] ChatPanelDropDown renders in ATAK
- [ ] Send/receive messages between two devices on same LAN
- [ ] Peer discovery working (peer_appeared events)

**Milestone 3 — Identity & persistence**
- [ ] RNS Identity persists across restarts
- [ ] Callsign pulled from ATAK MapView preferences
- [ ] Peer list persists in SharedPreferences

**Milestone 4 — Config management**
- [ ] Default config written on first launch
- [ ] Settings panel to edit RNS interfaces
- [ ] Bridge service restart on config change

**Milestone 5 — Extended transports**
- [ ] TCP bootstrap peer (internet)
- [ ] USB OTG RNode (LoRa)
- [ ] Test multi-hop relay

---

## References

- Reticulum documentation: https://reticulum.network/manual/
- Reticulum GitHub: https://github.com/markqvist/Reticulum
- Chaquopy documentation: https://chaquo.com/chaquopy/doc/current/
- ATAK Plugin Development Guide: https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV
- RNS Python API reference: https://markqvist.github.io/Reticulum/manual/api.html

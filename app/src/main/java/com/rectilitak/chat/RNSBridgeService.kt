package com.rectilitak.chat

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/**
 * Manages the Python RNS bridge lifecycle.
 * Runs as a singleton within ATAK's process (not an Android Service,
 * since plugin-declared services can't be started via startService).
 */
/**
 * @param atakContext ATAK's application context — for SharedPreferences and file storage
 * @param pluginContext The plugin's context — for Chaquopy (has bundled Python assets)
 */
class RNSBridgeService private constructor(
    private val atakContext: Context,
    private val pluginContext: Context
) {

    companion object {
        private const val TAG = "RNSBridgeService"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val BRIDGE_STARTUP_DELAY_MS = 2_500L
        private const val CONFIG_FILENAME = "reticulum_config"
        private const val PREFS_NAME = "rectilitak"

        @Volatile
        private var instance: RNSBridgeService? = null

        fun start(atakContext: Context, pluginContext: Context): RNSBridgeService {
            return instance ?: synchronized(this) {
                instance ?: RNSBridgeService(atakContext, pluginContext).also {
                    instance = it
                    it.startBridge()
                }
            }
        }

        fun getInstance(): RNSBridgeService? = instance

        fun stop() {
            instance?.shutdown()
            instance = null
        }
    }

    private val listeners = mutableListOf<BridgeEventListener>()
    private var socketClient: SocketClient? = null
    @Volatile
    private var running = false
    val identityManager = IdentityManager(atakContext)

    private val dataDir: File = File(atakContext.filesDir, "rectilitak")

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun startBridge() {
        running = true
        dataDir.mkdirs()
        ensureConfig()
        startPythonBridge()
    }

    fun shutdown() {
        running = false
        socketClient?.close()
    }

    // ------------------------------------------------------------------
    // Config management
    // ------------------------------------------------------------------

    /** RNS expects a directory containing a file named "config" */
    private fun getConfigDir(): File = File(dataDir, "rns_config")
    private fun getConfigFile(): File = File(getConfigDir(), "config")

    private fun ensureConfig() {
        getConfigDir().mkdirs()
        val configFile = getConfigFile()
        if (!configFile.exists()) {
            writeConfigFromPreferences()
            Log.d(TAG, "Default RNS config written to ${configFile.absolutePath}")
        }
    }

    fun writeConfigFromPreferences() {
        val prefs = atakContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val transportEnabled = prefs.getBoolean("rns.transport_enabled", false)
        val tcpEnabled = prefs.getBoolean("rns.tcp_enabled", false)
        val tcpHost = prefs.getString("rns.tcp_host", "amsterdam.connect.reticulum.network")
            ?: "amsterdam.connect.reticulum.network"
        val tcpPort = prefs.getString("rns.tcp_port", "4965") ?: "4965"

        val sb = StringBuilder()
        sb.appendLine("[reticulum]")
        sb.appendLine("enable_transport = $transportEnabled")
        sb.appendLine("share_instance   = false")
        sb.appendLine()
        sb.appendLine("[interfaces]")
        sb.appendLine()
        sb.appendLine("  [[Default Interface]]")
        sb.appendLine("  type    = AutoInterface")
        sb.appendLine("  enabled = true")

        if (tcpEnabled) {
            sb.appendLine()
            sb.appendLine("  [[TCP Bootstrap]]")
            sb.appendLine("  type             = TCPClientInterface")
            sb.appendLine("  enabled          = true")
            sb.appendLine("  target_host      = $tcpHost")
            sb.appendLine("  target_port      = $tcpPort")
        }

        getConfigFile().writeText(sb.toString())
        Log.d(TAG, "RNS config updated")
    }

    fun restartBridge() {
        Log.d(TAG, "Restarting bridge...")
        writeConfigFromPreferences()
        socketClient?.close()
    }

    // ------------------------------------------------------------------
    // Python bridge startup
    // ------------------------------------------------------------------

    private var socketThreadRunning = false

    private fun startPythonBridge() {
        Thread {
            try {
                if (!Python.isStarted()) {
                    // Chaquopy needs Application context (from ATAK) for SharedPreferences
                    // and plugin context for assets (build.json, Python stdlib)
                    val chaquopyContext = ChaquopyContextWrapper(atakContext, pluginContext)
                    Python.start(AndroidPlatform(chaquopyContext))
                }

                val py = Python.getInstance()
                val module = py.getModule("rns_bridge")

                Log.d(TAG, "Starting Python RNS bridge...")
                module.callAttr(
                    "main",
                    identityManager.getCallsign(),
                    identityManager.getUID(),
                    getConfigDir().absolutePath,
                    dataDir.absolutePath
                )
            } catch (e: Exception) {
                Log.e(TAG, "Python bridge crashed: ${e.message}", e)
            }

            if (running) {
                Log.w(TAG, "Bridge exited, restarting in ${RECONNECT_DELAY_MS}ms...")
                Thread.sleep(RECONNECT_DELAY_MS)
                startPythonBridge()
            }
        }.start()

        // Only start one socket thread
        if (!socketThreadRunning) {
            socketThreadRunning = true
            Thread {
                Thread.sleep(BRIDGE_STARTUP_DELAY_MS)
                connectSocket()
                socketThreadRunning = false
            }.start()
        }
    }

    // ------------------------------------------------------------------
    // Socket connection
    // ------------------------------------------------------------------

    private fun connectSocket() {
        while (running) {
            try {
                Log.d(TAG, "Connecting to bridge socket...")
                val client = SocketClient()
                client.connect()
                socketClient = client

                sendCommand(JSONObject().apply {
                    put("cmd", "set_identity")
                    put("callsign", identityManager.getCallsign())
                    put("uid", identityManager.getUID())
                })

                client.readLoop { event ->
                    Log.d(TAG, "Bridge event: $event")
                    dispatchEvent(event)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Socket error: ${e.message}, retrying...")
            }
            socketClient?.close()
            socketClient = null
            if (running) Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun sendMessage(room: String, body: String) {
        sendCommand(JSONObject().apply {
            put("cmd", "send")
            put("room", room)
            put("body", body)
        })
    }

    fun requestPeers() {
        sendCommand(JSONObject().apply {
            put("cmd", "get_peers")
        })
    }

    fun addListener(listener: BridgeEventListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: BridgeEventListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun sendCommand(cmd: JSONObject) {
        Thread {
            socketClient?.sendJson(cmd)
        }.start()
    }

    private fun dispatchEvent(event: JSONObject) {
        when (event.optString("event")) {
            "peer_appeared" -> {
                val hash = event.optString("hash", "")
                val callsign = event.optString("callsign", "")
                if (hash.isNotEmpty() && callsign.isNotEmpty()) {
                    identityManager.addPeer(hash, callsign)
                }
            }
            "peer_lost" -> {
                val hash = event.optString("hash", "")
                if (hash.isNotEmpty()) {
                    identityManager.removePeer(hash)
                }
            }
        }
        synchronized(listeners) {
            listeners.forEach { it.onEvent(event) }
        }
    }
}

package com.rectilitak.chat

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.rectilitak.chat.plugin.R
import org.json.JSONObject
import java.io.File

class RNSBridgeService : Service() {

    companion object {
        private const val TAG = "RNSBridgeService"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val BRIDGE_STARTUP_DELAY_MS = 2_500L
        private const val CONFIG_FILENAME = "reticulum_config"
    }

    inner class LocalBinder : Binder() {
        val service: RNSBridgeService get() = this@RNSBridgeService
    }

    private val binder = LocalBinder()
    private val listeners = mutableListOf<BridgeEventListener>()
    private var socketClient: SocketClient? = null
    private var running = false
    private var bridgeThread: Thread? = null
    private var socketThread: Thread? = null
    private lateinit var identityManager: IdentityManager

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            identityManager = IdentityManager(applicationContext)
            ensureConfig()
            startPythonBridge()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        socketClient?.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Config management
    // ------------------------------------------------------------------

    private fun getConfigFile(): File = File(filesDir, CONFIG_FILENAME)

    private fun ensureConfig() {
        val configFile = getConfigFile()
        if (!configFile.exists()) {
            writeConfigFromPreferences()
            Log.d(TAG, "Default RNS config written to ${configFile.absolutePath}")
        }
    }

    /**
     * Build and write the Reticulum config file based on current SharedPreferences.
     */
    fun writeConfigFromPreferences() {
        val prefs = getSharedPreferences("rectilitak", MODE_PRIVATE)
        val transportEnabled = prefs.getBoolean("rns.transport_enabled", false)
        val tcpEnabled = prefs.getBoolean("rns.tcp_enabled", false)
        val tcpHost = prefs.getString("rns.tcp_host", "amsterdam.connect.reticulum.network") ?: "amsterdam.connect.reticulum.network"
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

    /**
     * Restart the bridge with updated config. Called from preferences UI.
     */
    fun restartBridge() {
        Log.d(TAG, "Restarting bridge...")
        writeConfigFromPreferences()
        // Stop current bridge — it will auto-restart via the running loop
        socketClient?.close()
        // The Python bridge runs in a blocking call, so we need to
        // interrupt the socket to cause it to exit and restart
    }

    // ------------------------------------------------------------------
    // Python bridge startup
    // ------------------------------------------------------------------

    private fun startPythonBridge() {
        bridgeThread = Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }

                val py = Python.getInstance()
                val module = py.getModule("rns_bridge")

                Log.d(TAG, "Starting Python RNS bridge...")
                module.callAttr(
                    "main",
                    getCallsign(),
                    getUID(),
                    getConfigFile().absolutePath,
                    filesDir.absolutePath
                )
            } catch (e: Exception) {
                Log.e(TAG, "Python bridge crashed: ${e.message}", e)
            }

            if (running) {
                Log.w(TAG, "Bridge exited, restarting in ${RECONNECT_DELAY_MS}ms...")
                Thread.sleep(RECONNECT_DELAY_MS)
                startPythonBridge()
            }
        }.also { it.start() }

        socketThread = Thread {
            Thread.sleep(BRIDGE_STARTUP_DELAY_MS)
            connectSocket()
        }.also { it.start() }
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
                    put("callsign", getCallsign())
                    put("uid", getUID())
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
        listeners.add(listener)
    }

    fun removeListener(listener: BridgeEventListener) {
        listeners.remove(listener)
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
        listeners.forEach { it.onEvent(event) }
    }

    private fun getCallsign(): String = identityManager.getCallsign()

    private fun getUID(): String = identityManager.getUID()
}

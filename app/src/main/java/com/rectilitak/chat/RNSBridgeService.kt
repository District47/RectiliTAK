package com.rectilitak.chat

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

class RNSBridgeService : Service() {

    companion object {
        private const val TAG = "RNSBridgeService"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val BRIDGE_STARTUP_DELAY_MS = 2_500L
    }

    inner class LocalBinder : Binder() {
        val service: RNSBridgeService get() = this@RNSBridgeService
    }

    private val binder = LocalBinder()
    private val listeners = mutableListOf<BridgeEventListener>()
    private var socketClient: SocketClient? = null
    private var running = false

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
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
    // Python bridge startup
    // ------------------------------------------------------------------

    private fun startPythonBridge() {
        // Start the Python bridge on a background thread
        Thread {
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
                    null,  // config path
                    filesDir.absolutePath  // data_dir
                )
            } catch (e: Exception) {
                Log.e(TAG, "Python bridge crashed: ${e.message}", e)
            }

            // If bridge exits unexpectedly, restart
            if (running) {
                Log.w(TAG, "Bridge exited, restarting in ${RECONNECT_DELAY_MS}ms...")
                Thread.sleep(RECONNECT_DELAY_MS)
                startPythonBridge()
            }
        }.start()

        // Connect socket after giving Python time to bind
        Thread {
            Thread.sleep(BRIDGE_STARTUP_DELAY_MS)
            connectSocket()
        }.start()
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

                // Send identity
                sendCommand(JSONObject().apply {
                    put("cmd", "set_identity")
                    put("callsign", getCallsign())
                    put("uid", getUID())
                })

                // Read events from bridge
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
        listeners.forEach { it.onEvent(event) }
    }

    private fun getCallsign(): String {
        val prefs = getSharedPreferences("rectilitak", MODE_PRIVATE)
        return prefs.getString("callsign", "UNKNOWN") ?: "UNKNOWN"
    }

    private fun getUID(): String {
        val prefs = getSharedPreferences("rectilitak", MODE_PRIVATE)
        return prefs.getString("uid", java.util.UUID.randomUUID().toString()) ?: "UID-0000"
    }
}

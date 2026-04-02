package com.rectilitak.chat

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class SocketClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 17000
) {
    companion object {
        private const val TAG = "SocketClient"
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    fun connect() {
        socket = Socket(host, port)
        writer = PrintWriter(socket!!.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
        Log.d(TAG, "Connected to bridge at $host:$port")
    }

    fun sendJson(json: JSONObject) {
        try {
            writer?.println(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    fun readLoop(callback: (JSONObject) -> Unit) {
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                line?.let {
                    try {
                        callback(JSONObject(it))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse event: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read loop error: ${e.message}")
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }
}

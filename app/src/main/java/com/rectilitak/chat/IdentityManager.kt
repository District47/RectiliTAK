package com.rectilitak.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.atakmap.android.maps.MapView
import org.json.JSONObject

class IdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "IdentityManager"
        private const val PREFS_NAME = "rectilitak"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_UID = "uid"
        private const val KEY_PEERS = "peers_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the current callsign. Tries ATAK's MapView first, falls back to stored preference.
     */
    fun getCallsign(): String {
        // Try to read from ATAK's MapView
        try {
            val mapView = MapView.getMapView()
            if (mapView != null) {
                val callsign = mapView.deviceCallsign
                if (!callsign.isNullOrBlank()) {
                    // Cache it in preferences
                    prefs.edit().putString(KEY_CALLSIGN, callsign).apply()
                    return callsign
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read callsign from MapView: ${e.message}")
        }
        return prefs.getString(KEY_CALLSIGN, "UNKNOWN") ?: "UNKNOWN"
    }

    /**
     * Get the device UID. Tries ATAK's MapView first, falls back to stored/generated value.
     */
    fun getUID(): String {
        try {
            val mapView = MapView.getMapView()
            if (mapView != null) {
                val uid = mapView.selfMarker?.uid
                if (!uid.isNullOrBlank()) {
                    prefs.edit().putString(KEY_UID, uid).apply()
                    return uid
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read UID from MapView: ${e.message}")
        }
        var uid = prefs.getString(KEY_UID, null)
        if (uid == null) {
            uid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        return uid
    }

    /**
     * Store a discovered peer's callsign by their RNS hash.
     */
    fun addPeer(hash: String, callsign: String) {
        val peers = loadPeers().toMutableMap()
        peers[hash] = callsign
        savePeers(peers)
    }

    /**
     * Remove a peer by hash.
     */
    fun removePeer(hash: String) {
        val peers = loadPeers().toMutableMap()
        peers.remove(hash)
        savePeers(peers)
    }

    /**
     * Get all known peers as hash -> callsign map.
     */
    fun getPeers(): Map<String, String> = loadPeers()

    private fun loadPeers(): Map<String, String> {
        val json = prefs.getString(KEY_PEERS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load peers: ${e.message}")
            emptyMap()
        }
    }

    private fun savePeers(peers: Map<String, String>) {
        val obj = JSONObject()
        peers.forEach { (hash, callsign) ->
            obj.put(hash, callsign)
        }
        prefs.edit().putString(KEY_PEERS, obj.toString()).apply()
    }
}

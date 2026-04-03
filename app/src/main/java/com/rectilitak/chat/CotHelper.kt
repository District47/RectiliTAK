package com.rectilitak.chat

import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.comms.CommsMapComponent
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime
import org.json.JSONObject

object CotHelper {

    private const val TAG = "CotHelper"

    /**
     * Get the current device location as a JSON object suitable for sending over RNS.
     * Returns null if location is not available.
     */
    fun getSelfLocationJson(): JSONObject? {
        try {
            val mapView = MapView.getMapView() ?: run {
                Log.w(TAG, "MapView is null")
                return null
            }
            val self = mapView.selfMarker ?: run {
                Log.w(TAG, "Self marker is null")
                return null
            }
            val point = self.point
            val lat = point.latitude
            val lon = point.longitude

            // Check for invalid/unset coordinates
            if (lat == 0.0 && lon == 0.0) {
                Log.w(TAG, "Location is 0,0 — GPS may not have a fix yet")
                return null
            }

            val callsign = try {
                mapView.deviceCallsign ?: "UNKNOWN"
            } catch (_: Exception) { "UNKNOWN" }

            return JSONObject().apply {
                put("type", "cot_location")
                put("lat", lat)
                put("lon", lon)
                put("alt", point.altitude)
                put("ce", point.ce)
                put("le", point.le)
                put("callsign", callsign)
                put("uid", self.uid ?: "unknown")
                put("cot_type", self.type ?: "a-f-G-U-C")
                put("ts", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get self location: ${e.message}", e)
            return null
        }
    }

    /**
     * Inject a received location CoT event onto the ATAK map.
     * The marker will appear as a friendly ground unit with the sender's callsign.
     */
    fun injectLocationOnMap(data: JSONObject) {
        try {
            val lat = data.getDouble("lat")
            val lon = data.getDouble("lon")
            val alt = data.optDouble("alt", 0.0)
            val ce = data.optDouble("ce", 9999999.0)
            val le = data.optDouble("le", 9999999.0)
            val callsign = data.optString("callsign", "RNS-Peer")
            val uid = data.optString("uid", "rns-" + data.optString("sender_hash", "unknown").take(12))
            val cotType = data.optString("cot_type", "a-f-G-U-C")

            val now = CoordinatedTime()
            val stale = CoordinatedTime(now.milliseconds + 300000) // 5 min stale

            val event = CotEvent()
            event.type = cotType
            event.uid = "rns-$uid"
            event.time = now
            event.start = now
            event.stale = stale
            event.how = "m-g" // machine-generated
            event.setPoint(CotPoint(lat, lon, alt, ce, le))

            val detail = CotDetail("detail")

            val contact = CotDetail("contact")
            contact.setAttribute("callsign", "$callsign (RNS)")
            detail.addChild(contact)

            val group = CotDetail("__group")
            group.setAttribute("name", "Cyan")
            group.setAttribute("role", "Team Member")
            detail.addChild(group)

            event.detail = detail

            // Dispatch to ATAK's internal CoT handler
            CotMapComponent.getInternalDispatcher().dispatch(event)
            Log.d(TAG, "Injected CoT for $callsign at $lat, $lon")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject CoT: ${e.message}", e)
        }
    }
}

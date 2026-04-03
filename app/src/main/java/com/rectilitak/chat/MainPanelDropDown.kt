package com.rectilitak.chat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.maps.MapView
import com.rectilitak.chat.plugin.R
import org.json.JSONObject

class MainPanelDropDown(
    mapView: MapView,
    private val pluginContext: Context
) : DropDownReceiver(mapView), OnStateListener, BridgeEventListener {

    companion object {
        const val SHOW_CHAT = "com.rectilitak.chat.SHOW_CHAT"
        private const val TAG = "MainPanelDropDown"
    }

    private val container: FrameLayout
    private val mainView: View
    private val chatPanel: ChatPanelDropDown
    private val locationView: View

    // Main page views
    private val mainMyAddress: TextView
    private val mainConnectionStatus: TextView
    private val mainPeerCount: TextView

    // Location page views
    private val locAutoShare: Switch
    private val locFrequency: Spinner
    private val locShareWith: Spinner
    private val locCotIcon: Spinner
    private val locStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var autoShareRunnable: Runnable? = null

    private val contactManager = ContactManager(mapView.context)
    private val groupManager = GroupManager(mapView.context)

    private val frequencies = listOf("30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes")
    private val frequencyMs = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)

    private val iconEntries = listOf(
        // MIL-STD-2525
        IconEntry("Friendly Ground Unit", "a-f-G-U-C"),
        IconEntry("Friendly Ground Vehicle", "a-f-G-E-V"),
        IconEntry("Friendly Ground Infantry", "a-f-G-U-C-I"),
        IconEntry("Friendly Air", "a-f-A-M-F"),
        IconEntry("Friendly Sea", "a-f-S-X"),
        IconEntry("Neutral Ground", "a-n-G"),
        IconEntry("Unknown Ground", "a-u-G"),
        // APRS
        IconEntry("Car", "aprs-car", R.drawable.aprs_car, true),
        IconEntry("Jeep", "aprs-jeep", R.drawable.aprs_jeep, true),
        IconEntry("Truck", "aprs-truck", R.drawable.aprs_truck, true),
        IconEntry("Van", "aprs-van", R.drawable.aprs_van, true),
        IconEntry("Motorcycle", "aprs-motorcycle", R.drawable.aprs_motorcycle, true),
        IconEntry("Bicycle", "aprs-bike", R.drawable.aprs_bike, true),
        IconEntry("Bus", "aprs-bus", R.drawable.aprs_bus, true),
        IconEntry("Semi Truck", "aprs-truck18", R.drawable.aprs_truck18, true),
        IconEntry("RV", "aprs-rv", R.drawable.aprs_rv, true),
        IconEntry("Helicopter", "aprs-helicopter", R.drawable.aprs_helicopter, true),
        IconEntry("Small Aircraft", "aprs-aircraft-sm", R.drawable.aprs_aircraft_small, true),
        IconEntry("Large Aircraft", "aprs-aircraft-lg", R.drawable.aprs_aircraft_large, true),
        IconEntry("Power Boat", "aprs-boat", R.drawable.aprs_boat, true),
        IconEntry("Sailboat", "aprs-sailboat", R.drawable.aprs_sailboat, true),
        IconEntry("Jogger", "aprs-jogger", R.drawable.aprs_jogger, true),
        IconEntry("Emergency", "aprs-emergency", R.drawable.aprs_emergency, true),
        IconEntry("Ambulance", "aprs-ambulance", R.drawable.aprs_ambulance, true),
        IconEntry("Fire Truck", "aprs-firetruck", R.drawable.aprs_fire_truck, true),
        IconEntry("Fire", "aprs-fire", R.drawable.aprs_fire, true),
        IconEntry("Police", "aprs-police", R.drawable.aprs_police, true),
        IconEntry("Hospital", "aprs-hospital", R.drawable.aprs_hospital, true),
        IconEntry("Aid Station", "aprs-aid", R.drawable.aprs_aid_station, true),
        IconEntry("House", "aprs-house", R.drawable.aprs_house_hf, true),
    )

    init {
        container = FrameLayout(pluginContext)

        val inflater = LayoutInflater.from(pluginContext)

        // Main page
        mainView = inflater.inflate(R.layout.main_panel, null)
        mainMyAddress = mainView.findViewById(R.id.mainMyAddress)
        mainConnectionStatus = mainView.findViewById(R.id.mainConnectionStatus)
        mainPeerCount = mainView.findViewById(R.id.mainPeerCount)

        mainView.findViewById<LinearLayout>(R.id.btnMessenger).setOnClickListener {
            showPage("chat")
        }
        mainView.findViewById<LinearLayout>(R.id.btnLocation).setOnClickListener {
            showPage("location")
        }

        mainMyAddress.setOnClickListener {
            val addr = mainMyAddress.text.toString().removePrefix("My address: ")
            if (addr != "...") {
                val clipboard = pluginContext.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RNS Address", addr))
                Toast.makeText(mapView.context, "Address copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Chat page (reuse existing ChatPanelDropDown as a view provider)
        chatPanel = ChatPanelDropDown(mapView, pluginContext)
        chatPanel.rootView.findViewById<Button>(R.id.chatBackButton).setOnClickListener {
            showPage("main")
        }

        // Location page
        locationView = inflater.inflate(R.layout.location_panel, null)
        locAutoShare = locationView.findViewById(R.id.locAutoShare)
        locFrequency = locationView.findViewById(R.id.locFrequency)
        locShareWith = locationView.findViewById(R.id.locShareWith)
        locCotIcon = locationView.findViewById(R.id.locCotIcon)
        locStatus = locationView.findViewById(R.id.locStatus)

        locationView.findViewById<Button>(R.id.locBackButton).setOnClickListener {
            showPage("main")
        }
        locationView.findViewById<Button>(R.id.locShareNow).setOnClickListener {
            shareLocationNow()
        }

        // Frequency spinner
        locFrequency.adapter = ArrayAdapter(pluginContext,
            android.R.layout.simple_spinner_item, frequencies).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        locFrequency.setSelection(2) // default 2 minutes

        // Icon spinner adapter set lazily when location page is shown

        // Auto-share toggle
        locAutoShare.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startAutoShare()
            } else {
                stopAutoShare()
            }
        }

        // Start on main page
        container.addView(mainView)
    }

    private fun showPage(page: String) {
        container.removeAllViews()
        when (page) {
            "main" -> container.addView(mainView)
            "chat" -> {
                chatPanel.onShow()
                container.addView(chatPanel.rootView)
            }
            "location" -> {
                rebuildShareWithSpinner()
                if (locCotIcon.adapter == null || locCotIcon.adapter.count == 0) {
                    locCotIcon.adapter = CotIconAdapter(mapView.context, pluginContext, iconEntries)
                }
                container.addView(locationView)
            }
        }
    }

    private fun rebuildShareWithSpinner() {
        val items = mutableListOf("All Contacts")
        contactManager.getContacts().forEach { items.add(it.name) }
        groupManager.getGroups().forEach { items.add("Group: ${it.name}") }
        locShareWith.adapter = ArrayAdapter(pluginContext,
            android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun getSelectedCotType(): String {
        val idx = locCotIcon.selectedItemPosition
        return if (idx >= 0 && idx < iconEntries.size) iconEntries[idx].cotType else "a-f-G-U-C"
    }

    private fun getShareTargets(): Pair<String?, List<String>?> {
        val idx = locShareWith.selectedItemPosition
        val item = locShareWith.selectedItem?.toString() ?: return null to null

        return when {
            item == "All Contacts" -> {
                val addrs = contactManager.getContacts().map { it.address }
                null to addrs
            }
            item.startsWith("Group: ") -> {
                val groupName = item.removePrefix("Group: ")
                val group = groupManager.getGroups().find { it.name == groupName }
                null to group?.members
            }
            else -> {
                val contact = contactManager.getContacts().find { it.name == item }
                contact?.address to null
            }
        }
    }

    private fun shareLocationNow() {
        val location = CotHelper.getSelfLocationJson()
        if (location == null) {
            locStatus.text = "Location not available — make sure GPS has a fix"
            return
        }

        // Override the CoT type with user selection
        location.put("cot_type", getSelectedCotType())

        val (dest, members) = getShareTargets()
        if (dest == null && (members == null || members.isEmpty())) {
            locStatus.text = "No recipients selected"
            return
        }

        RNSBridgeService.getInstance()?.sendLocation(dest, members, location)
        locStatus.text = "Location shared"
    }

    private fun startAutoShare() {
        stopAutoShare()
        val idx = locFrequency.selectedItemPosition
        val interval = if (idx >= 0 && idx < frequencyMs.size) frequencyMs[idx] else 120_000L

        autoShareRunnable = object : Runnable {
            override fun run() {
                if (locAutoShare.isChecked) {
                    shareLocationNow()
                    handler.postDelayed(this, interval)
                }
            }
        }
        // Share immediately, then repeat
        handler.post(autoShareRunnable!!)
        locStatus.text = "Auto-sharing every ${frequencies[idx.coerceIn(0, frequencies.size - 1)]}"
    }

    private fun stopAutoShare() {
        autoShareRunnable?.let { handler.removeCallbacks(it) }
        autoShareRunnable = null
        locStatus.text = "Auto-share stopped"
    }

    // ------------------------------------------------------------------
    // Bridge lifecycle
    // ------------------------------------------------------------------

    private var bridgeStarted = false

    private fun ensureBridgeStarted() {
        if (!bridgeStarted) {
            bridgeStarted = true
            Thread {
                RNSBridgeService.start(mapView.context, pluginContext)
                RNSBridgeService.getInstance()?.addListener(this@MainPanelDropDown)
                // Also register the chat panel as a listener
                RNSBridgeService.getInstance()?.addListener(chatPanel)
            }.start()
        }
    }

    // ------------------------------------------------------------------
    // BridgeEventListener — update main page status
    // ------------------------------------------------------------------

    override fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "ready" -> {
                val address = event.optString("address", "")
                mapView.post {
                    mainConnectionStatus.text = "RNS bridge connected"
                    mainConnectionStatus.setTextColor(
                        pluginContext.resources.getColor(R.color.chat_accent, null))
                    if (address.isNotEmpty()) {
                        mainMyAddress.text = "My address: $address"
                    }
                }
            }
            "address" -> {
                val address = event.optString("address", "")
                mapView.post {
                    if (address.isNotEmpty()) {
                        mainMyAddress.text = "My address: $address"
                    }
                }
            }
            "location" -> {
                // Inject received location onto map
                mapView.post {
                    CotHelper.injectLocationOnMap(event)
                }
            }
        }
        // Forward to chat panel too
        chatPanel.onEvent(event)
    }

    // ------------------------------------------------------------------
    // DropDown
    // ------------------------------------------------------------------

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == SHOW_CHAT) {
            ensureBridgeStarted()
            showPage("main")
            showDropDown(
                container,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                this
            )
        }
    }

    override fun disposeImpl() {
        stopAutoShare()
        RNSBridgeService.getInstance()?.removeListener(this)
        RNSBridgeService.getInstance()?.removeListener(chatPanel)
        chatPanel.dispose()
    }

    override fun onDropDownSelectionRemoved() {}
    override fun onDropDownVisible(v: Boolean) {}
    override fun onDropDownSizeChanged(width: Double, height: Double) {}
    override fun onDropDownClose() {}
}

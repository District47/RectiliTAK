package com.rectilitak.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.maps.MapView
import com.rectilitak.chat.plugin.R
import org.json.JSONObject

class ChatPanelDropDown(
    mapView: MapView,
    private val pluginContext: Context
) : DropDownReceiver(mapView), OnStateListener, BridgeEventListener {

    companion object {
        const val SHOW_CHAT = "com.rectilitak.chat.SHOW_CHAT"
    }

    private val rootView: View
    private val messageList: ListView
    private val messageInput: EditText
    private val sendButton: Button
    private val roomSpinner: Spinner
    private val connectionStatus: TextView
    private val messages = mutableListOf<String>()
    private val adapter: ArrayAdapter<String>

    private val rooms = listOf("All Chat", "Team", "Command")

    private var bridgeService: RNSBridgeService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as RNSBridgeService.LocalBinder
            bridgeService = localBinder.service
            bridgeService?.addListener(this@ChatPanelDropDown)
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService?.removeListener(this@ChatPanelDropDown)
            bridgeService = null
            serviceBound = false
        }
    }

    init {
        val inflater = LayoutInflater.from(pluginContext)
        rootView = inflater.inflate(R.layout.chat_panel, null)

        messageList = rootView.findViewById(R.id.messageList)
        messageInput = rootView.findViewById(R.id.messageInput)
        sendButton = rootView.findViewById(R.id.sendButton)
        roomSpinner = rootView.findViewById(R.id.roomSpinner)
        connectionStatus = rootView.findViewById(R.id.connectionStatus)

        adapter = ArrayAdapter(pluginContext, android.R.layout.simple_list_item_1, messages)
        messageList.adapter = adapter

        roomSpinner.adapter = ArrayAdapter(
            pluginContext,
            android.R.layout.simple_spinner_item,
            rooms
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        sendButton.setOnClickListener { sendMessage() }

        messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // Bind to the bridge service
        val intent = Intent(mapView.context, RNSBridgeService::class.java)
        mapView.context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun sendMessage() {
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return
        val room = roomSpinner.selectedItem as String

        appendMessage("[You] $body")
        messageInput.setText("")

        bridgeService?.sendMessage(room, body)
    }

    // BridgeEventListener
    override fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "message" -> {
                val from = event.optString("from", "?")
                val body = event.optString("body", "")
                val room = event.optString("room", "")
                mapView.post { appendMessage("[$room] $from: $body") }
            }
            "peer_appeared" -> {
                val cs = event.optString("callsign", "?")
                mapView.post { appendMessage("*** $cs joined the net") }
            }
            "peer_lost" -> {
                val cs = event.optString("callsign", "?")
                mapView.post { appendMessage("*** $cs left the net") }
            }
            "ready" -> {
                mapView.post {
                    connectionStatus.text = "RNS bridge connected"
                    connectionStatus.setTextColor(pluginContext.resources.getColor(R.color.chat_accent))
                    appendMessage("*** RNS bridge ready")
                }
            }
            "peers" -> {
                val peers = event.optJSONObject("peers")
                if (peers != null) {
                    val count = peers.length()
                    mapView.post { appendMessage("*** $count peer(s) on the net") }
                }
            }
        }
    }

    private fun appendMessage(msg: String) {
        messages.add(msg)
        adapter.notifyDataSetChanged()
        messageList.smoothScrollToPosition(messages.size - 1)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == SHOW_CHAT) {
            showDropDown(
                rootView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                this
            )
        }
    }

    override fun disposeImpl() {
        if (serviceBound) {
            bridgeService?.removeListener(this)
            try {
                mapView.context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            serviceBound = false
        }
    }

    override fun onDropDownSelectionRemoved() {}
    override fun onDropDownVisible(v: Boolean) {}
    override fun onDropDownSizeChanged(width: Double, height: Double) {}
    override fun onDropDownClose() {}
}

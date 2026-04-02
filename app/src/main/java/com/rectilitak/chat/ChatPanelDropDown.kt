package com.rectilitak.chat

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
    private val myAddress: TextView
    private val destAddress: EditText
    private val messages = mutableListOf<String>()
    private val adapter: ArrayAdapter<String>

    private val modes = listOf("Direct", "All Chat", "Team", "Command")

    init {
        val inflater = LayoutInflater.from(pluginContext)
        rootView = inflater.inflate(R.layout.chat_panel, null)

        messageList = rootView.findViewById(R.id.messageList)
        messageInput = rootView.findViewById(R.id.messageInput)
        sendButton = rootView.findViewById(R.id.sendButton)
        roomSpinner = rootView.findViewById(R.id.roomSpinner)
        connectionStatus = rootView.findViewById(R.id.connectionStatus)
        myAddress = rootView.findViewById(R.id.myAddress)
        destAddress = rootView.findViewById(R.id.destAddress)

        adapter = ArrayAdapter(pluginContext, android.R.layout.simple_list_item_1, messages)
        messageList.adapter = adapter

        roomSpinner.adapter = ArrayAdapter(
            pluginContext,
            android.R.layout.simple_spinner_item,
            modes
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Show/hide destination address field based on mode
        roomSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val isDirect = modes[pos] == "Direct"
                destAddress.visibility = if (isDirect) View.VISIBLE else View.GONE
                messageInput.hint = if (isDirect) "Direct message..." else "Message..."
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

        // Tap address to copy
        myAddress.setOnClickListener {
            val addr = myAddress.text.toString().removePrefix("My address: ")
            if (addr != "...") {
                val clipboard = pluginContext.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RNS Address", addr))
                Toast.makeText(mapView.context, "Address copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Register as listener on the bridge singleton
        RNSBridgeService.getInstance()?.addListener(this)
    }

    private fun sendMessage() {
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return
        val mode = roomSpinner.selectedItem as String

        if (mode == "Direct") {
            val dest = destAddress.text.toString().trim()
            if (dest.isEmpty()) {
                Toast.makeText(mapView.context, "Enter a destination address", Toast.LENGTH_SHORT).show()
                return
            }
            appendMessage("[DM -> ${dest.take(16)}...] $body")
            messageInput.setText("")
            RNSBridgeService.getInstance()?.sendDirect(dest, body)
        } else {
            appendMessage("[You] $body")
            messageInput.setText("")
            RNSBridgeService.getInstance()?.sendMessage(mode, body)
        }
    }

    // BridgeEventListener
    override fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "message" -> {
                val from = event.optString("from", "?")
                val body = event.optString("body", "")
                val room = event.optString("room", "")
                val senderHash = event.optString("sender_hash", "")
                val prefix = if (room == "Direct") {
                    "[DM <- ${senderHash.take(16)}] $from"
                } else {
                    "[$room] $from"
                }
                mapView.post { appendMessage("$prefix: $body") }
            }
            "peer_appeared" -> {
                val cs = event.optString("callsign", "?")
                val hash = event.optString("hash", "")
                mapView.post { appendMessage("*** $cs joined (${hash.take(16)})") }
            }
            "peer_lost" -> {
                val cs = event.optString("callsign", "?")
                mapView.post { appendMessage("*** $cs left the net") }
            }
            "ready" -> {
                val address = event.optString("address", "")
                mapView.post {
                    connectionStatus.text = "RNS bridge connected"
                    connectionStatus.setTextColor(
                        pluginContext.resources.getColor(R.color.chat_accent, null))
                    if (address.isNotEmpty()) {
                        myAddress.text = "My address: $address"
                    }
                    appendMessage("*** RNS bridge ready")
                }
            }
            "address" -> {
                val address = event.optString("address", "")
                mapView.post {
                    if (address.isNotEmpty()) {
                        myAddress.text = "My address: $address"
                    }
                }
            }
            "status" -> {
                val body = event.optString("body", "")
                mapView.post { appendMessage("*** $body") }
            }
            "error" -> {
                val body = event.optString("body", "")
                mapView.post { appendMessage("!!! $body") }
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
        RNSBridgeService.getInstance()?.removeListener(this)
    }

    override fun onDropDownSelectionRemoved() {}
    override fun onDropDownVisible(v: Boolean) {}
    override fun onDropDownSizeChanged(width: Double, height: Double) {}
    override fun onDropDownClose() {}
}

package com.rectilitak.chat

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
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

    val rootView: View
    private val messageList: ListView
    private val messageInput: EditText
    private val sendButton: Button
    private val roomSpinner: Spinner
    private val connectionStatus: TextView
    private val myAddress: TextView
    private val destAddress: EditText
    private val directRow: LinearLayout
    private val contactsButton: Button
    private val contactLabel: TextView
    private val shareLocationButton: Button
    private val messages = mutableListOf<String>()
    private val adapter: ArrayAdapter<String>
    private val contactManager: ContactManager
    private val groupManager: GroupManager

    // Spinner items: "Direct", "New Group...", then saved groups
    private val spinnerItems = mutableListOf<String>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    // Currently selected group (null = Direct mode)
    private var selectedGroup: ChatGroup? = null

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
        directRow = rootView.findViewById(R.id.directRow)
        contactsButton = rootView.findViewById(R.id.contactsButton)
        contactLabel = rootView.findViewById(R.id.contactLabel)
        shareLocationButton = rootView.findViewById(R.id.shareLocationButton)

        contactManager = ContactManager(mapView.context)
        groupManager = GroupManager(mapView.context)

        adapter = object : ArrayAdapter<String>(pluginContext, android.R.layout.simple_list_item_1, messages) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view as TextView
                val prefs = mapView.context.getSharedPreferences("rectilitak_prefs", Context.MODE_PRIVATE)
                val fontSize = prefs.getString("chat.font_size", "14")?.toFloatOrNull() ?: 14f
                tv.textSize = fontSize
                tv.setTextColor(pluginContext.resources.getColor(R.color.chat_text, null))
                return view
            }
        }
        messageList.adapter = adapter

        rebuildSpinner()

        roomSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val item = spinnerItems[pos]
                when {
                    item == "Direct" -> {
                        selectedGroup = null
                        directRow.visibility = View.VISIBLE
                        messageInput.hint = "Direct message..."
                    }
                    item == "+ New Group..." -> {
                        showCreateGroupDialog()
                        // Reset selection to Direct
                        roomSpinner.setSelection(0)
                    }
                    else -> {
                        // It's a group
                        selectedGroup = groupManager.getGroups().find { it.name == item }
                        directRow.visibility = View.GONE
                        contactLabel.visibility = View.GONE
                        messageInput.hint = "Message to $item..."
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sendButton.setOnClickListener { sendMessage() }
        contactsButton.setOnClickListener { showContactsDialog() }
        shareLocationButton.setOnClickListener { shareLocation() }

        destAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val addr = s?.toString()?.trim() ?: ""
                val name = contactManager.getNameForAddress(addr)
                if (name != null) {
                    contactLabel.text = name
                    contactLabel.visibility = View.VISIBLE
                } else {
                    contactLabel.visibility = View.GONE
                }
            }
        })

        messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendMessage()
                true
            } else {
                false
            }
        }

        myAddress.setOnClickListener {
            val addr = myAddress.text.toString().removePrefix("My address: ")
            if (addr != "...") {
                val clipboard = pluginContext.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RNS Address", addr))
                Toast.makeText(mapView.context, "Address copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Long-press on spinner to manage/delete a group
        roomSpinner.setOnLongClickListener {
            val group = selectedGroup
            if (group != null) {
                showGroupOptionsDialog(group)
            }
            true
        }
    }

    private fun rebuildSpinner() {
        spinnerItems.clear()
        spinnerItems.add("Direct")
        spinnerItems.add("+ New Group...")
        groupManager.getGroups().forEach { spinnerItems.add(it.name) }

        spinnerAdapter = ArrayAdapter(
            pluginContext,
            android.R.layout.simple_spinner_item,
            spinnerItems
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        roomSpinner.adapter = spinnerAdapter
    }

    // ------------------------------------------------------------------
    // Create Group dialog
    // ------------------------------------------------------------------

    private fun showCreateGroupDialog() {
        val contacts = contactManager.getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(mapView.context, "Add contacts first", Toast.LENGTH_SHORT).show()
            return
        }

        val layout = LinearLayout(mapView.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val nameInput = EditText(mapView.context).apply {
            hint = "Group name"
            setSingleLine()
        }
        layout.addView(nameInput)

        val label = TextView(mapView.context).apply {
            text = "Select members:"
            setPadding(0, 24, 0, 8)
        }
        layout.addView(label)

        val checked = BooleanArray(contacts.size)
        val names = contacts.map { "${it.name} (${it.address.take(12)}...)" }.toTypedArray()

        AlertDialog.Builder(mapView.context)
            .setTitle("Create Group")
            .setView(layout)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Create") { _, _ ->
                val groupName = nameInput.text.toString().trim()
                if (groupName.isEmpty()) {
                    Toast.makeText(mapView.context, "Enter a group name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val members = contacts.filterIndexed { i, _ -> checked[i] }.map { it.address }
                if (members.isEmpty()) {
                    Toast.makeText(mapView.context, "Select at least one member", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                groupManager.addGroup(groupName, members)
                rebuildSpinner()
                // Select the new group
                val idx = spinnerItems.indexOf(groupName)
                if (idx >= 0) roomSpinner.setSelection(idx)
                Toast.makeText(mapView.context, "Group '$groupName' created", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupOptionsDialog(group: ChatGroup) {
        val memberNames = group.members.map { addr ->
            contactManager.getNameForAddress(addr) ?: addr.take(16) + "..."
        }
        val info = "Members: ${memberNames.joinToString(", ")}"

        AlertDialog.Builder(mapView.context)
            .setTitle(group.name)
            .setMessage(info)
            .setNeutralButton("Delete Group") { _, _ ->
                AlertDialog.Builder(mapView.context)
                    .setTitle("Delete ${group.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        groupManager.removeGroup(group.id)
                        selectedGroup = null
                        rebuildSpinner()
                        roomSpinner.setSelection(0)
                        Toast.makeText(mapView.context, "Group deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Contacts dialog
    // ------------------------------------------------------------------

    private fun showContactsDialog() {
        val contacts = contactManager.getContacts()

        val items = mutableListOf<String>()
        items.add("+ Add new contact")
        contacts.forEach { items.add("${it.name}  (${it.address.take(12)}...)") }

        AlertDialog.Builder(mapView.context)
            .setTitle("Contacts")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showAddContactDialog()
                } else {
                    val contact = contacts[which - 1]
                    showContactActionDialog(contact)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showContactActionDialog(contact: Contact) {
        AlertDialog.Builder(mapView.context)
            .setTitle(contact.name)
            .setMessage("Address: ${contact.address}")
            .setPositiveButton("Send Message") { _, _ ->
                setDestContact(contact)
                roomSpinner.setSelection(0)
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(mapView.context)
                    .setTitle("Delete ${contact.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        contactManager.removeContact(contact.address)
                        Toast.makeText(mapView.context, "Contact deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddContactDialog() {
        val layout = LinearLayout(mapView.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val nameInput = EditText(mapView.context).apply {
            hint = "Name / Callsign"
            setSingleLine()
        }
        layout.addView(nameInput)

        val addressInput = EditText(mapView.context).apply {
            hint = "RNS Address (hex)"
            setSingleLine()
            textSize = 13f
        }
        layout.addView(addressInput)

        AlertDialog.Builder(mapView.context)
            .setTitle("Add Contact")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isEmpty() || address.isEmpty()) {
                    Toast.makeText(mapView.context, "Name and address required", Toast.LENGTH_SHORT).show()
                } else {
                    contactManager.addContact(name, address)
                    Toast.makeText(mapView.context, "Contact saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Destination helpers
    // ------------------------------------------------------------------

    /** The real RNS address behind the display text */
    private var destRealAddress: String? = null

    private fun setDestContact(contact: Contact) {
        destRealAddress = contact.address
        destAddress.setText(contact.name)
        contactLabel.text = contact.address
        contactLabel.visibility = View.VISIBLE
    }

    private fun getDestAddress(): String {
        // If we set a contact, use the stored real address
        val real = destRealAddress
        val display = destAddress.text.toString().trim()
        // If the display text was edited away from the contact name, clear the stored address
        if (real != null) {
            val name = contactManager.getNameForAddress(real)
            if (name != null && display == name) {
                return real
            }
        }
        destRealAddress = null
        return display
    }

    // ------------------------------------------------------------------
    // Share Location
    // ------------------------------------------------------------------

    private fun shareLocation() {
        val location = CotHelper.getSelfLocationJson()
        if (location == null) {
            Toast.makeText(mapView.context, "Location not available — make sure GPS has a fix", Toast.LENGTH_LONG).show()
            return
        }

        val group = selectedGroup
        if (group != null) {
            appendMessage("[${group.name}] You shared your location")
            RNSBridgeService.getInstance()?.sendLocation(
                null, group.members, location, group.id, group.name
            )
        } else {
            val dest = getDestAddress()
            if (dest.isEmpty()) {
                Toast.makeText(mapView.context, "Enter a destination address", Toast.LENGTH_SHORT).show()
                return
            }
            val contactName = contactManager.getNameForAddress(dest)
            val label = contactName ?: dest.take(16) + "..."
            appendMessage("[DM -> $label] Shared location")
            RNSBridgeService.getInstance()?.sendLocation(dest, null, location)
        }
    }

    // ------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------

    private fun sendMessage() {
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) return

        val group = selectedGroup
        if (group != null) {
            appendMessage("[${group.name}] You: $body")
            messageInput.setText("")
            RNSBridgeService.getInstance()?.sendGroup(
                group.members, body, group.id, group.name
            )
        } else {
            val dest = getDestAddress()
            if (dest.isEmpty()) {
                Toast.makeText(mapView.context, "Enter a destination address", Toast.LENGTH_SHORT).show()
                return
            }
            val contactName = contactManager.getNameForAddress(dest)
            val label = contactName ?: dest.take(16) + "..."
            appendMessage("[DM -> $label] $body")
            messageInput.setText("")
            RNSBridgeService.getInstance()?.sendDirect(dest, body)
        }
    }

    // ------------------------------------------------------------------
    // BridgeEventListener
    // ------------------------------------------------------------------

    override fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "message" -> {
                val from = event.optString("from", "?")
                val body = event.optString("body", "")
                val room = event.optString("room", "")
                val senderHash = event.optString("sender_hash", "")
                val groupName = event.optString("group_name", "")
                val contactName = if (senderHash.isNotEmpty()) {
                    contactManager.getNameForAddress(senderHash)
                } else null
                val displayFrom = contactName ?: from

                val prefix = when (room) {
                    "Group" -> "[$groupName] $displayFrom"
                    "Direct" -> "[DM <- ${contactName ?: senderHash.take(16)}] $displayFrom"
                    else -> "[$room] $displayFrom"
                }
                mapView.post { appendMessage("$prefix: $body") }
            }
            "peer_appeared" -> {
                val cs = event.optString("callsign", "?")
                val hash = event.optString("hash", "")
                val contactName = contactManager.getNameForAddress(hash)
                val label = contactName ?: cs
                mapView.post { appendMessage("*** $label discovered (${hash.take(16)})") }
            }
            "location" -> {
                val from = event.optString("from", "?")
                val senderHash = event.optString("sender_hash", "")
                val contactName = if (senderHash.isNotEmpty()) {
                    contactManager.getNameForAddress(senderHash)
                } else null
                val displayFrom = contactName ?: from
                mapView.post {
                    appendMessage("*** $displayFrom shared their location")
                }
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
        }
    }

    private var showTimestamps = false

    private fun applyChatPreferences() {
        val prefs = mapView.context.getSharedPreferences("rectilitak_prefs", Context.MODE_PRIVATE)
        val fontSize = prefs.getString("chat.font_size", "14")?.toFloatOrNull() ?: 14f
        showTimestamps = prefs.getBoolean("chat.show_timestamps", false)
        val compact = prefs.getBoolean("chat.compact_mode", false)

        messageList.dividerHeight = if (compact) 0 else 1
        // Update adapter text size
        adapter.notifyDataSetChanged()
        messageInput.textSize = fontSize
    }

    private fun appendMessage(msg: String) {
        val display = if (showTimestamps) {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            "[$time] $msg"
        } else {
            msg
        }
        messages.add(display)
        adapter.notifyDataSetChanged()
        messageList.smoothScrollToPosition(messages.size - 1)
    }

    // ------------------------------------------------------------------
    // DropDown lifecycle
    // ------------------------------------------------------------------

    override fun onReceive(context: Context, intent: Intent) {
        // Not used directly — MainPanelDropDown manages navigation
    }

    fun onShow() {
        rebuildSpinner()
        applyChatPreferences()
    }

    override fun disposeImpl() {
        RNSBridgeService.getInstance()?.removeListener(this)
    }

    override fun onDropDownSelectionRemoved() {}
    override fun onDropDownVisible(v: Boolean) {}
    override fun onDropDownSizeChanged(width: Double, height: Double) {}
    override fun onDropDownClose() {}
}

package com.rectilitak.chat

import org.json.JSONObject

interface BridgeEventListener {
    fun onEvent(event: JSONObject)
}

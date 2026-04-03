package com.rectilitak.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class Contact(val name: String, val address: String)

class ContactManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "rectilitak_contacts"
        private const val KEY_CONTACTS = "contacts_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContacts(): List<Contact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().map { address ->
                Contact(name = obj.getString(address), address = address)
            }.sortedBy { it.name.lowercase() }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addContact(name: String, address: String) {
        val clean = address.trim().replace(" ", "").replace("<", "").replace(">", "")
        val contacts = loadMap().toMutableMap()
        contacts[clean] = name.trim()
        save(contacts)
    }

    fun removeContact(address: String) {
        val clean = address.trim().replace(" ", "").replace("<", "").replace(">", "")
        val contacts = loadMap().toMutableMap()
        contacts.remove(clean)
        save(contacts)
    }

    fun getNameForAddress(address: String): String? {
        val clean = address.trim().replace(" ", "").replace("<", "").replace(">", "")
        return loadMap()[clean]
    }

    private fun loadMap(): Map<String, String> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun save(contacts: Map<String, String>) {
        val obj = JSONObject()
        contacts.forEach { (address, name) -> obj.put(address, name) }
        prefs.edit().putString(KEY_CONTACTS, obj.toString()).apply()
    }
}

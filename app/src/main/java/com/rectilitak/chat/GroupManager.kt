package com.rectilitak.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ChatGroup(
    val id: String,
    val name: String,
    val members: List<String>  // RNS addresses
)

class GroupManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "rectilitak_groups"
        private const val KEY_GROUPS = "groups_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGroups(): List<ChatGroup> {
        val json = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val membersArr = obj.getJSONArray("members")
                val members = (0 until membersArr.length()).map { j -> membersArr.getString(j) }
                ChatGroup(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    members = members
                )
            }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addGroup(name: String, members: List<String>): ChatGroup {
        val group = ChatGroup(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name.trim(),
            members = members.map { it.trim().replace(" ", "").replace("<", "").replace(">", "") }
        )
        val groups = getGroups().toMutableList()
        groups.add(group)
        save(groups)
        return group
    }

    fun removeGroup(id: String) {
        val groups = getGroups().filter { it.id != id }
        save(groups)
    }

    fun getGroup(id: String): ChatGroup? = getGroups().find { it.id == id }

    private fun save(groups: List<ChatGroup>) {
        val arr = JSONArray()
        groups.forEach { group ->
            val obj = JSONObject()
            obj.put("id", group.id)
            obj.put("name", group.name)
            val membersArr = JSONArray()
            group.members.forEach { membersArr.put(it) }
            obj.put("members", membersArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }
}

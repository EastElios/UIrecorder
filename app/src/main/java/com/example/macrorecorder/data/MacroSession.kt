package com.example.macrorecorder.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 一次录制会话，包含多个事件
 */
data class MacroSession(
    val id: Long = System.currentTimeMillis(),
    val name: String = "录制 $id",
    val events: List<MacroEvent> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val eventCount: Int get() = events.size

    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MacroSession = Gson().fromJson(json, MacroSession::class.java)
        fun listFromJson(json: String): List<MacroSession> =
            Gson().fromJson(json, object : TypeToken<List<MacroSession>>() {}.type)
        fun listToJson(sessions: List<MacroSession>): String = Gson().toJson(sessions)
    }
}
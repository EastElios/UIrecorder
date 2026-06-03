package com.example.macrorecorder.storage

import android.content.Context
import com.example.macrorecorder.data.MacroSession
import java.io.File

/**
 * 使用 JSON 文件持久化录制会话
 */
object MacroStorage {

    private const val FILE_NAME = "macro_sessions.json"

    private fun getFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun saveSessions(context: Context, sessions: List<MacroSession>) {
        val json = MacroSession.listToJson(sessions)
        getFile(context).writeText(json)
    }

    fun loadSessions(context: Context): List<MacroSession> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            MacroSession.listFromJson(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSession(context: Context, session: MacroSession) {
        val sessions = loadSessions(context).toMutableList()
        sessions.add(0, session)  // 最新的放在最前面
        saveSessions(context, sessions)
    }

    fun deleteSession(context: Context, sessionId: Long) {
        val sessions = loadSessions(context).filter { it.id != sessionId }
        saveSessions(context, sessions)
    }
}
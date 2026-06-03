package com.example.macrorecorder.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 单个操作事件
 */
data class MacroEvent(
    val action: Int,          // MotionEvent action: 0=DOWN, 2=MOVE, 1=UP
    val x: Float,             // 屏幕x坐标
    val y: Float,             // 屏幕y坐标
    val relativeTime: Long    // 相对于录制开始的时间(毫秒)
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }

    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MacroEvent = Gson().fromJson(json, MacroEvent::class.java)
        fun listFromJson(json: String): List<MacroEvent> =
            Gson().fromJson(json, object : TypeToken<List<MacroEvent>>() {}.type)
        fun listToJson(events: List<MacroEvent>): String = Gson().toJson(events)
    }
}
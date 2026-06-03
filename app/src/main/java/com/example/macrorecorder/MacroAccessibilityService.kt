package com.example.macrorecorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.macrorecorder.data.MacroEvent
import com.example.macrorecorder.data.MacroSession

/**
 * 无障碍服务 - 核心功能：
 * 1. 监听无障碍事件用于录制辅助
 * 2. 通过 dispatchGesture 回放录制的操作
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroA11yService"
        var instance: MacroAccessibilityService? = null
            private set

        // 回放状态
        var isReplaying = false
            private set
        var stopReplayRequested = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")

        // 获取屏幕尺寸
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display: Display = wm.defaultDisplay
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            displayWidth = point.x
            displayHeight = point.y
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 主要用于录制时辅助收集信息，非核心录制逻辑
        // 核心录制由 RecordingOverlayService 的悬浮窗完成
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
        stopReplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "无障碍服务已销毁")
    }

    /**
     * 回放一个录制会话
     */
    fun replaySession(session: MacroSession, onComplete: () -> Unit) {
        if (session.events.isEmpty()) {
            onComplete()
            return
        }

        isReplaying = true
        stopReplayRequested = false

        Log.d(TAG, "开始回放，共 ${session.events.size} 个事件")

        // 按相对时间依次执行
        scheduleNextEvent(session.events, 0, session.events[0].relativeTime, onComplete)
    }

    private fun scheduleNextEvent(
        events: List<MacroEvent>,
        index: Int,
        baseTime: Long,
        onComplete: () -> Unit
    ) {
        if (stopReplayRequested || index >= events.size) {
            isReplaying = false
            Log.d(TAG, "回放结束")
            handler.post(onComplete)
            return
        }

        val event = events[index]
        val delay = if (index == 0) 0L else event.relativeTime - events[index - 1].relativeTime

        handler.postDelayed({
            if (stopReplayRequested) {
                isReplaying = false
                handler.post(onComplete)
                return@postDelayed
            }

            // 执行事件：根据 action 类型决定如何分发
            executeEvent(event, events, index) {
                scheduleNextEvent(events, index + 1, baseTime, onComplete)
            }
        }, delay.coerceAtMost(5000)) // 最多延迟 5 秒，防止异常
    }

    private fun executeEvent(
        event: MacroEvent,
        allEvents: List<MacroEvent>,
        index: Int,
        onDone: () -> Unit
    ) {
        val x = event.x.coerceIn(0f, displayWidth.toFloat())
        val y = event.y.coerceIn(0f, displayHeight.toFloat())

        when {
            // 如果是 DOWN 事件，看看后面有没有 MOVE，决定是单点还是路径
            event.action == MacroEvent.ACTION_DOWN -> {
                // 收集从当前 DOWN 到下一个 UP 之间的所有 MOVE 事件
                val gestureEvents = mutableListOf(event)
                var nextIndex = index + 1
                while (nextIndex < allEvents.size) {
                    val next = allEvents[nextIndex]
                    if (next.action == MacroEvent.ACTION_MOVE || next.action == MacroEvent.ACTION_DOWN) {
                        gestureEvents.add(next)
                        nextIndex++
                    } else if (next.action == MacroEvent.ACTION_UP) {
                        gestureEvents.add(next)
                        nextIndex++
                        break
                    } else {
                        break
                    }
                }

                // 把收集到的事件一起作为一个手势发出
                dispatchGesturePath(gestureEvents, onDone)
            }

            // 单独的 UP 或 MOVE（不应该出现，但做防御性处理）
            event.action == MacroEvent.ACTION_UP -> {
                dispatchTap(x, y, onDone)
            }

            else -> {
                onDone()
            }
        }
    }

    /**
     * 发送单点点击手势
     */
    private fun dispatchTap(x: Float, y: Float, onDone: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post(onDone)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post(onDone)
            }
        }, null)
    }

    /**
     * 发送路径手势（包含滑动）
     */
    private fun dispatchGesturePath(events: List<MacroEvent>, onDone: () -> Unit) {
        if (events.isEmpty()) {
            onDone()
            return
        }

        val first = events.first()
        val last = events.last()

        val path = Path().apply {
            moveTo(first.x, first.y)
            for (e in events) {
                if (e.action == MacroEvent.ACTION_MOVE) {
                    lineTo(e.x, e.y)
                }
            }
            // 确保最后到达 UP 位置
            lineTo(last.x, last.y)
        }

        val duration = (last.relativeTime - first.relativeTime).coerceAtLeast(50)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post(onDone)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post(onDone)
            }
        }, null)
    }

    /**
     * 停止回放
     */
    fun stopReplay() {
        stopReplayRequested = true
        isReplaying = false
    }
}
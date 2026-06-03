package com.example.macrorecorder

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.macrorecorder.data.MacroEvent

/**
 * 悬浮窗录制服务
 * 通过全屏透明覆盖层捕获触控事件，录制所有操作
 * 录制完成后通过 AccessibilityService 转发手势到下层应用
 */
class RecordingOverlayService : Service() {

    companion object {
        private const val TAG = "RecordingOverlay"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"

        var isRecording = false
            private set

        /** 当前正在录制的会话事件列表 */
        var currentEvents: MutableList<MacroEvent> = mutableListOf()
            private set

        /** 通知录制状态变化的回调 */
        var onRecordingStateChanged: ((Boolean) -> Unit)? = null
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var recordingIndicator: TextView? = null
    private var controlButton: TextView? = null

    private var recordingStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ValueAnimator? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    /**
     * 开始录制：显示全屏透明覆盖层捕获触控
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        currentEvents.clear()
        recordingStartTime = System.currentTimeMillis()

        // 创建全屏覆盖层
        overlayView = FrameLayout(this).apply {
            // 半透明红色背景，让用户知道正在录制
            setBackgroundColor(Color.argb(30, 255, 0, 0))

            setOnTouchListener { _, event ->
                handleTouchEvent(event)
                true
            }
        }

        // 录制指示器 - 顶部
        recordingIndicator = TextView(this).apply {
            text = "⏺ 正在录制..."
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(180, 220, 50, 50))
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
        }

        // 停止按钮 - 底部
        controlButton = TextView(this).apply {
            text = "停止录制"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.argb(200, 220, 50, 50))
            setPadding(48, 20, 48, 20)
            gravity = Gravity.CENTER

            setOnClickListener {
                stopRecording()
            }
        }

        overlayView?.addView(
            recordingIndicator,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = 80
            }
        )

        overlayView?.addView(
            controlButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = 120
            }
        )

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(overlayView, overlayLayoutParams)

        // 脉冲动画
        startPulseAnimation()

        onRecordingStateChanged?.invoke(true)
        Log.d(TAG, "录制已开始")
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val action: Int = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MacroEvent.ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MacroEvent.ACTION_UP
            MotionEvent.ACTION_MOVE -> MacroEvent.ACTION_MOVE
            else -> return
        }

        val x = event.rawX
        val y = event.rawY
        val relativeTime = System.currentTimeMillis() - recordingStartTime

        currentEvents.add(MacroEvent(action, x, y, relativeTime))

        // 当手势结束时（UP），通过无障碍服务转发到下层应用
        if (action == MacroEvent.ACTION_UP) {
            forwardGestureToApp()
        }
    }

    /**
     * 将最近一个手势通过 AccessibilityService 转发到下层应用
     * 转发期间临时禁用覆盖层的触控接收
     */
    private fun forwardGestureToApp() {
        val service = MacroAccessibilityService.instance
        if (service == null || currentEvents.isEmpty()) return

        // 收集最近一次手势（从最后一个 DOWN 到当前 UP）
        val gestureEvents = mutableListOf<MacroEvent>()
        for (i in currentEvents.indices.reversed()) {
            val e = currentEvents[i]
            gestureEvents.add(0, e)
            if (e.action == MacroEvent.ACTION_DOWN) break
        }

        if (gestureEvents.isEmpty()) return

        // 临时禁用覆盖层触控，让手势能穿透到下层应用
        setOverlayTouchable(false)

        val first = gestureEvents.first()
        val last = gestureEvents.last()
        val duration = (last.relativeTime - first.relativeTime).coerceAtLeast(50)

        val path = Path().apply {
            moveTo(first.x, first.y)
            for (e in gestureEvents) {
                if (e.action == MacroEvent.ACTION_MOVE) {
                    lineTo(e.x, e.y)
                }
            }
            if (last.action != MacroEvent.ACTION_DOWN) {
                lineTo(last.x, last.y)
            }
        }

        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, duration
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        service.dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    setOverlayTouchable(true)
                }

                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    setOverlayTouchable(true)
                }
            },
            null
        )
    }

    /**
     * 设置覆盖层是否可触控
     * 通过修改 WindowManager.LayoutParams 的 FLAG_NOT_TOUCHABLE 实现
     */
    private fun setOverlayTouchable(touchable: Boolean) {
        val view = overlayView ?: return
        val params = overlayLayoutParams ?: return

        handler.post {
            try {
                if (touchable) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    view.alpha = 1.0f
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    view.alpha = 0.3f  // 视觉反馈：变淡表示转发中
                }
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "更新覆盖层状态失败", e)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        stopPulseAnimation()
        removeOverlay()
        stopSelf()

        onRecordingStateChanged?.invoke(false)
        Log.d(TAG, "录制已停止，共 ${currentEvents.size} 个事件")
    }

    private fun startPulseAnimation() {
        val indicator = recordingIndicator ?: return
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                indicator.alpha = animation.animatedValue as Float
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "移除覆盖层失败", e)
        }
        overlayView = null
        recordingIndicator = null
        controlButton = null
        overlayLayoutParams = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录制服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "宏录制服务运行中"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("宏录制")
        .setContentText("正在录制操作...")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()
}
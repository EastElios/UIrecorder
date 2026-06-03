package com.example.macrorecorder

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.macrorecorder.data.MacroSession
import com.example.macrorecorder.storage.MacroStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {

    private lateinit var switchAutoReplay: SwitchMaterial
    private lateinit var btnRecord: MaterialButton
    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvEmptyHint: MaterialTextView
    private lateinit var rvSessions: RecyclerView
    private lateinit var sessionsAdapter: SessionAdapter
    private lateinit var prefs: SharedPreferences

    private var sessions: List<MacroSession> = emptyList()
    private var autoReplayEnabled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        autoReplayEnabled = prefs.getBoolean("auto_replay", false)

        initViews()
        setupListeners()
        loadSessions()

        // 监听录制状态变化
        RecordingOverlayService.onRecordingStateChanged = { recording ->
            runOnUiThread {
                updateUI(recording)
                if (!recording) {
                    loadSessions()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
        updateUI(RecordingOverlayService.isRecording)

        // 如果开启了自动执行，且有录制内容，且无障碍服务已开启，则自动回放
        if (autoReplayEnabled && sessions.isNotEmpty() && isAccessibilityServiceEnabled()
            && !RecordingOverlayService.isRecording
        ) {
            handler.postDelayed({
                if (!MacroAccessibilityService.isReplaying) {
                    replaySession(sessions.first())
                }
            }, 600)
        }
    }

    private fun initViews() {
        switchAutoReplay = findViewById(R.id.switch_auto_replay)
        btnRecord = findViewById(R.id.btn_record)
        tvStatus = findViewById(R.id.tv_status)
        tvEmptyHint = findViewById(R.id.tv_empty_hint)
        rvSessions = findViewById(R.id.rv_sessions)

        switchAutoReplay.isChecked = autoReplayEnabled

        sessionsAdapter = SessionAdapter(
            onPlayClick = { session -> replaySession(session) },
            onDeleteClick = { session -> deleteSession(session) }
        )
        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = sessionsAdapter
    }

    private fun setupListeners() {
        switchAutoReplay.setOnCheckedChangeListener { _, isChecked ->
            autoReplayEnabled = isChecked
            prefs.edit().putBoolean("auto_replay", isChecked).apply()
        }

        btnRecord.setOnClickListener {
            if (RecordingOverlayService.isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
                return
            }
        }

        // 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 最小化应用，启动录制
        moveTaskToBack(true)
        val intent = Intent(this, RecordingOverlayService::class.java).apply {
            action = "START_RECORDING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI(true)
        Toast.makeText(this, "录制已开始，请执行操作", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingOverlayService::class.java).apply {
            action = "STOP_RECORDING"
        }
        startService(intent)
        updateUI(false)

        // 保存录制结果
        val events = RecordingOverlayService.currentEvents.toList()
        if (events.isNotEmpty()) {
            val session = MacroSession(
                name = "录制 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
                events = events
            )
            MacroStorage.addSession(this, session)
            RecordingOverlayService.currentEvents.clear()
            loadSessions()
            Toast.makeText(this, "已保存，共 ${events.size} 个操作", Toast.LENGTH_SHORT).show()
        }
    }

    private fun replaySession(session: MacroSession) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (MacroAccessibilityService.isReplaying) {
            MacroAccessibilityService.instance?.stopReplay()
            Toast.makeText(this, "已停止回放", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "开始回放...", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)

        MacroAccessibilityService.instance?.replaySession(session) {
            runOnUiThread {
                Toast.makeText(this, "回放完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteSession(session: MacroSession) {
        MacroStorage.deleteSession(this, session.id)
        loadSessions()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    private fun loadSessions() {
        sessions = MacroStorage.loadSessions(this)
        sessionsAdapter.submitList(sessions)
        tvEmptyHint.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        rvSessions.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateUI(recording: Boolean) {
        if (recording) {
            btnRecord.text = "停止录制"
            btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            tvStatus.text = "录制中..."
            tvStatus.setTextColor(Color.parseColor("#D32F2F"))
        } else {
            btnRecord.text = "开始录制"
            btnRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1976D2"))
            tvStatus.text = if (isAccessibilityServiceEnabled()) "就绪" else "无障碍服务未开启"
            tvStatus.setTextColor(
                if (isAccessibilityServiceEnabled()) Color.parseColor("#4CAF50")
                else Color.parseColor("#FF9800")
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${MacroAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service) || enabledServices.contains(packageName)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            }
        }
    }
}
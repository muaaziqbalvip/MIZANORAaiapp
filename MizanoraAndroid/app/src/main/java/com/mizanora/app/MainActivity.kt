package com.mizanora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var taskInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var watchModeCheckbox: CheckBox
    private var voice: VoiceManager? = null

    private var lastProjectionResultCode: Int = -1
    private var lastProjectionData: Intent? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> log(if (granted) "✅ Microphone granted." else "❌ Microphone denied.") }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            lastProjectionResultCode = result.resultCode
            lastProjectionData = result.data
            startCaptureService(null)
            log("✅ Screen capture permission granted. Mizanora is running (see the notification).")
        } else {
            log("❌ Screen capture permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        taskInput = findViewById(R.id.taskInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        watchModeCheckbox = findViewById(R.id.watchModeCheckbox)
        voice = VoiceManager(this)

        refreshStatus()

        findViewById<Button>(R.id.btnSaveApiKey).setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isBlank()) {
                log("Type a key first.")
            } else {
                SecureConfig.setApiKey(this, key)
                apiKeyInput.text.clear()
                log("✅ API key saved (encrypted on this phone, never leaves it except to call Gemini).")
                refreshStatus()
            }
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            log("Opened Accessibility settings — enable 'Mizanora' in the list.")
        }

        findViewById<Button>(R.id.btnEnableMic).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                log("✅ Microphone already granted.")
            }
        }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(intent)
            log("Stopped Mizanora.")
        }

        findViewById<Button>(R.id.btnSendTask).setOnClickListener {
            val task = taskInput.text.toString().trim()
            if (TextUtils.isEmpty(task)) {
                log("Type a task first.")
            } else {
                sendTask(task)
            }
        }

        findViewById<Button>(R.id.btnSpeakTask).setOnClickListener {
            log("Listening…")
            voice?.listenOnce(this) { heard ->
                if (heard.isNullOrBlank()) {
                    log("Didn't catch that.")
                } else {
                    taskInput.setText(heard)
                    sendTask(heard)
                }
            }
        }
    }

    private fun sendTask(task: String) {
        if (!SecureConfig.hasApiKey(this)) {
            log("⚠️ Save your Gemini API key first (above).")
            return
        }
        if (lastProjectionResultCode == -1 || lastProjectionData == null) {
            log("⚠️ Start Mizanora (step 3) first — no active screen-capture session.")
            return
        }
        startCaptureService(task)
        log("▶ Task sent: $task")
    }

    private fun startCaptureService(task: String?) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, lastProjectionResultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, lastProjectionData)
            putExtra(ScreenCaptureService.EXTRA_WATCH_MODE, watchModeCheckbox.isChecked)
            if (task != null) {
                action = ScreenCaptureService.ACTION_RUN_TASK
                putExtra(ScreenCaptureService.EXTRA_TASK, task)
            }
        }
        startForegroundService(intent)
    }

    private fun refreshStatus() {
        statusText.text = if (SecureConfig.hasApiKey(this)) {
            "✅ API key saved. Grant permissions below, then start Mizanora."
        } else {
            "⚠️ No Gemini API key yet — paste one above and save it. Get one free at aistudio.google.com/apikey"
        }
    }

    private fun log(line: String) {
        logText.append("$line\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        voice?.shutdown()
    }
}

package com.mizanora.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Runs as a visible foreground service (Android requires the notification —
 * see notes in AndroidManifest.xml). Holds the screen-capture session and,
 * when given a task, runs a "screenshot -> ask Gemini -> perform one action"
 * loop until Gemini reports the task done or a safety cap is hit.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "MizanoraCapture"
        private const val CHANNEL_ID = "mizanora_capture"
        private const val NOTIF_ID = 1

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TASK = "task"
        const val EXTRA_WATCH_MODE = "watch_mode"
        const val ACTION_RUN_TASK = "com.mizanora.app.RUN_TASK"
        const val ACTION_STOP = "com.mizanora.app.STOP"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler
    private var voice: VoiceManager? = null

    @Volatile private var isTaskRunning = false
    @Volatile private var watchModeEnabled = false
    private val watchRunnable = object : Runnable {
        override fun run() {
            if (watchModeEnabled && !isTaskRunning) {
                runWatchTick()
            }
            if (watchModeEnabled) {
                bgHandler.postDelayed(this, SecureConfig.getWatchIntervalSec(this@ScreenCaptureService) * 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("MizanoraCaptureThread").apply { start() }
        bgHandler = Handler(bgThread.looper)
        voice = VoiceManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                watchModeEnabled = false
                bgHandler.removeCallbacks(watchRunnable)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification())
                ensureProjection(intent)
                setWatchMode(intent.getBooleanExtra(EXTRA_WATCH_MODE, false))
                bgHandler.post { runTaskLoop(task) }
                return START_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                ensureProjection(intent)
                setWatchMode(intent?.getBooleanExtra(EXTRA_WATCH_MODE, false) ?: false)
                return START_STICKY
            }
        }
    }

    private fun setWatchMode(enabled: Boolean) {
        val wasEnabled = watchModeEnabled
        watchModeEnabled = enabled
        if (enabled && !wasEnabled) {
            voice?.speak("Okay, I'll keep an eye on your screen.")
            bgHandler.postDelayed(watchRunnable, SecureConfig.getWatchIntervalSec(this) * 1000L)
        } else if (!enabled && wasEnabled) {
            bgHandler.removeCallbacks(watchRunnable)
        }
    }

    /** One idle-mode glance. Only speaks if something is actually worth mentioning. */
    private fun runWatchTick() {
        val shot = grabScreenshot() ?: return
        val apiKey = SecureConfig.getApiKey(this)
        val note = GeminiClient.observeScreen(apiKey, shot) ?: return
        Log.i(TAG, "Watch note: $note")
        voice?.speak(note)
    }

    private fun ensureProjection(intent: Intent?) {
        if (mediaProjection != null || intent == null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) return

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MizanoraCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )
        Log.i(TAG, "MediaProjection + VirtualDisplay ready ($width x $height).")
    }

    /** Blocking; call from a background thread. Returns null if no frame is ready yet. */
    private fun grabScreenshot(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e(TAG, "grabScreenshot failed", e)
            null
        } finally {
            image.close()
        }
    }

    private fun runTaskLoop(task: String) {
        isTaskRunning = true
        try {
            val apiKey = SecureConfig.getApiKey(this)
            if (apiKey.isBlank()) {
                voice?.speak("I don't have a Gemini API key yet — save one in the app first.")
                return
            }
            val maxSteps = SecureConfig.getMaxSteps(this) // default 40 — big multi-step tasks are fine
            val history = mutableListOf<String>()
            voice?.speak("Okay, on it: $task")

            repeat(maxSteps) { stepIndex ->
                // Give the virtual display a moment to produce a fresh frame.
                Thread.sleep(400)
                val shot = grabScreenshot()
                if (shot == null) {
                    Log.w(TAG, "No frame available yet at step $stepIndex.")
                    return@repeat
                }

                val decision = GeminiClient.decideNextStep(apiKey, shot, task, history)
                if (decision == null) {
                    voice?.speak("I couldn't reach Gemini to plan the next step.")
                    return
                }

                if (decision.say.isNotBlank()) voice?.speak(decision.say)

                val a11y = AutomationAccessibilityService.instance
                when (decision.action) {
                    "tap" -> a11y?.tap(decision.x, decision.y)
                    "swipe" -> a11y?.swipe(decision.x, decision.y, decision.x2, decision.y2)
                    "type" -> a11y?.typeIntoFocusedField(decision.text)
                    "open_app" -> a11y?.openApp(decision.packageName)
                    "back" -> a11y?.pressBack()
                    "home" -> a11y?.pressHome()
                    "speak" -> { /* already spoken above */ }
                    "done" -> return
                    else -> Log.w(TAG, "Unknown action: ${decision.action}")
                }

                if (a11y == null) {
                    voice?.speak("The accessibility service isn't enabled, so I can't act on screen — only look and talk.")
                    return
                }

                history.add(decision.action)

                // Long task: a periodic check-in so it doesn't feel like it went silent.
                if (stepIndex > 0 && stepIndex % 8 == 0) {
                    voice?.speak("Still working on it — step ${stepIndex + 1}.")
                }
            }
            voice?.speak("Stopping — hit the $maxSteps-step safety limit for one task. You can raise this in Settings.")
        } finally {
            isTaskRunning = false
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mizanora")
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        voice?.shutdown()
        bgThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

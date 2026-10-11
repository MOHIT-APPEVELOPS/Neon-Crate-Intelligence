package com.neon.crateintelligence.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.neon.crateintelligence.R
import com.neon.crateintelligence.engine.CrateProbabilityEngine
import com.neon.crateintelligence.receiver.ServerResetAlarmReceiver
import com.neon.crateintelligence.timing.PrecisionTimingSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingCrateHudService : Service() {

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private var spinCount = 0
    private var consecutiveNonMythics = 0

    private val handler = Handler(Looper.getMainLooper())
    private var loopRunnable: Runnable? = null
    private var toneGen: ToneGenerator? = null
    private var lastSecondBeep = -1
    private var isTickArmed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initToneGenerator()
        startNotification()
        scheduleResetAlarm()
        initHudOverlay()

        CoroutineScope(Dispatchers.IO).launch {
            PrecisionTimingSyncEngine.syncAtomicClock()
        }
    }

    private fun initToneGenerator() {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (ignored: Exception) {}
    }

    private fun startNotification() {
        val channelId = "crate_timing_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Crate Precision Tracker", NotificationManager.IMPORTANCE_LOW)
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Neon Crate Precision Active")
            .setContentText("05:30 AM Reset & Tick Sync Running")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(104, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(104, notification)
        }
    }

    private fun scheduleResetAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServerResetAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val millisUntilReset = PrecisionTimingSyncEngine.getMillisUntilServerReset()
            val triggerTime = System.currentTimeMillis() + millisUntilReset

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (ignored: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initHudOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        hudView = inflater.inflate(R.layout.layout_crate_hud, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 220
        }

        val tvCountdown = hudView?.findViewById<TextView>(R.id.tvCountdown)
        val tvActionBanner = hudView?.findViewById<TextView>(R.id.tvActionBanner)
        val tvStats = hudView?.findViewById<TextView>(R.id.tvStats)
        val statusIndicator = hudView?.findViewById<View>(R.id.statusIndicator)
        val btnTapSpin = hudView?.findViewById<Button>(R.id.btnTapSpin)
        val btnReset = hudView?.findViewById<Button>(R.id.btnReset)

        fun updateUI() {
            val odds = CrateProbabilityEngine.calculateCurrentOdds(consecutiveNonMythics)
            val millisUntilReset = PrecisionTimingSyncEngine.getMillisUntilServerReset()
            val hours = (millisUntilReset / (1000 * 60 * 60)) % 24
            val minutes = (millisUntilReset / (1000 * 60)) % 60
            val seconds = (millisUntilReset / 1000) % 60

            val currentMsInSec = PrecisionTimingSyncEngine.getCurrentSecondMillisecond()
            val isSweetSpot = PrecisionTimingSyncEngine.isTickExecutionSweetSpot()
            val isSeedFresh = PrecisionTimingSyncEngine.isGlobalSeedFreshWindow()

            tvStats?.text = String.format("Odds: %.1f%% | Pity: %d", odds, consecutiveNonMythics)
            tvCountdown?.text = String.format("Reset: %02d:%02d:%02d", hours, minutes, seconds)

            // 57, 58, 59 सेकंड पर बीप साउंड (3... 2... 1...)
            if (seconds.toInt() in 57..59) {
                if (lastSecondBeep != seconds.toInt()) {
                    lastSecondBeep = seconds.toInt()
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
                }
            } else if (seconds.toInt() == 0 && lastSecondBeep != 0) {
                lastSecondBeep = 0
                toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                triggerVibrate(100)
            }

            if (isSeedFresh || isSweetSpot) {
                tvActionBanner?.text = "TAP NOW (TICK READY)"
                tvActionBanner?.setBackgroundColor(Color.parseColor("#00FF66"))
                tvActionBanner?.setTextColor(Color.parseColor("#000000"))
                statusIndicator?.setBackgroundColor(Color.parseColor("#00FF66"))

                if (isSweetSpot && !isTickArmed) {
                    triggerVibrate(35)
                    isTickArmed = true
                }
            } else {
                tvActionBanner?.text = String.format("WAIT (%03d ms)", currentMsInSec)
                tvActionBanner?.setBackgroundColor(Color.parseColor("#1E293B"))
                tvActionBanner?.setTextColor(Color.parseColor("#94A3B8"))
                statusIndicator?.setBackgroundColor(
                    if (PrecisionTimingSyncEngine.isClockCalibrated) Color.parseColor("#38BDF8") else Color.parseColor("#EF4444")
                )
                isTickArmed = false
            }
        }

        btnTapSpin?.setOnClickListener {
            spinCount++
            consecutiveNonMythics++
            updateUI()
        }

        btnReset?.setOnClickListener {
            consecutiveNonMythics = 0
            updateUI()
        }

        hudView?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (event.rawX - touchX).toInt()
                        params.y = initY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(hudView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager?.addView(hudView, params)

        loopRunnable = object : Runnable {
            override fun run() {
                updateUI()
                handler.postDelayed(this, 50)
            }
        }
        handler.post(loopRunnable!!)
    }

    private fun triggerVibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        loopRunnable?.let { handler.removeCallbacks(it) }
        toneGen?.release()
        if (hudView != null && windowManager != null) {
            windowManager?.removeView(hudView)
        }
        super.onDestroy()
    }
}

package com.clawsses.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clawsses.phone.ClawssesApp
import com.clawsses.phone.MainActivity
import com.clawsses.phone.R

/**
 * Foreground service that keeps the app alive when the phone screen is off.
 * This ensures:
 * - Bluetooth connection to glasses stays active
 * - Voice recognition (SpeechRecognizer) can be triggered
 * - WebSocket connection to server is maintained
 */
class GlassesConnectionService : Service() {

    companion object {
        private const val TAG = "GlassesService"
        private const val CHANNEL_ID = "glasses_connection"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "Clawsses::VoiceRecognition"
        private const val ACTION_HOLD_WAKE_LOCK = "com.clawsses.phone.action.HOLD_WAKE_LOCK"
        private const val ACTION_RELEASE_WAKE_LOCK = "com.clawsses.phone.action.RELEASE_WAKE_LOCK"
        private const val EXTRA_WAKE_LOCK_REASON = "wake_lock_reason"
        private const val EXTRA_WAKE_LOCK_TIMEOUT_MS = "wake_lock_timeout_ms"

        fun start(context: Context) {
            val intent = Intent(context, GlassesConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GlassesConnectionService::class.java)
            context.stopService(intent)
        }

        fun holdWakeLock(context: Context, reason: WakeLockReason, timeoutMs: Long) {
            context.startForegroundService(
                Intent(context, GlassesConnectionService::class.java)
                    .setAction(ACTION_HOLD_WAKE_LOCK)
                    .putExtra(EXTRA_WAKE_LOCK_REASON, reason.name)
                    .putExtra(EXTRA_WAKE_LOCK_TIMEOUT_MS, timeoutMs),
            )
        }

        fun releaseWakeLock(context: Context, reason: WakeLockReason) {
            context.startForegroundService(
                Intent(context, GlassesConnectionService::class.java)
                    .setAction(ACTION_RELEASE_WAKE_LOCK)
                    .putExtra(EXTRA_WAKE_LOCK_REASON, reason.name),
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockLeases = WakeLockLeaseRegistry()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val leaseExpiry = Runnable { applyWakeLockLeases() }

    override fun onCreate() {
        super.onCreate()
        (application as ClawssesApp).runtime.start()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        startForeground(NOTIFICATION_ID, createNotification())
        when (intent?.action) {
            ACTION_HOLD_WAKE_LOCK -> intent.wakeLockReason()?.let { reason ->
                wakeLockLeases.acquire(
                    reason = reason,
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                    durationMs = intent.getLongExtra(EXTRA_WAKE_LOCK_TIMEOUT_MS, 1L).coerceAtLeast(1L),
                )
                applyWakeLockLeases()
            }
            ACTION_RELEASE_WAKE_LOCK -> intent.wakeLockReason()?.let { reason ->
                wakeLockLeases.release(reason, android.os.SystemClock.elapsedRealtime())
                applyWakeLockLeases()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        mainHandler.removeCallbacks(leaseExpiry)
        wakeLockLeases.clear()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glasses Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps glasses connection active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clawsses")
            .setContentText("Connected — voice input active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun applyWakeLockLeases() {
        mainHandler.removeCallbacks(leaseExpiry)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val expirationMs = wakeLockLeases.nextExpiration(nowMs)
        if (expirationMs == null) {
            releaseWakeLock()
            return
        }
        val remainingMs = (expirationMs - nowMs).coerceAtLeast(1L)
        // Recreate the non-reference-counted lock so renewing a lease also renews
        // Android's timeout instead of relying on acquire() behavior while held.
        releaseWakeLock()
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(remainingMs)
        mainHandler.postDelayed(leaseExpiry, remainingMs)
        Log.i(TAG, "Wake lock leased for ${remainingMs}ms")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun Intent.wakeLockReason(): WakeLockReason? =
        getStringExtra(EXTRA_WAKE_LOCK_REASON)?.let { encoded ->
            runCatching { WakeLockReason.valueOf(encoded) }.getOrNull()
        }
}

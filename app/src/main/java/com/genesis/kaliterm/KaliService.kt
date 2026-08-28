package com.genesis.kaliterm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground Service: mantiene la sesión de Kali viva en SEGUNDO PLANO.
 *
 * Sin esto, Android mata los procesos cuando sales de la app. Con un
 * foreground service + wakelock, los procesos (escaneos, servidores,
 * metasploit, etc.) siguen corriendo con la app minimizada o la pantalla
 * apagada. Es exactamente lo que hace Termux.
 *
 * La PERSISTENCIA del rootfs y de lo instalado NO depende de esto: eso vive
 * en el almacenamiento interno privado (/data/data/<pkg>/files) y sobrevive
 * a reinicios/apagados. Este servicio solo evita que te maten los PROCESOS
 * en ejecución mientras la app no está en primer plano.
 */
class KaliService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKELOCK -> acquireWakeLock()
        }
        startForeground(NOTIF_ID, buildNotification())
        // START_STICKY: si el sistema nos mata por memoria, se reinicia el servicio.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val held = wakeLock?.isHeld == true
        val text = if (held) "Sesión activa · wakelock ON" else "Sesión activa en segundo plano"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kali Terminal")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, "Detener", pending(ACTION_STOP))
            .addAction(0, if (held) "Soltar wakelock" else "Wakelock", pending(ACTION_WAKELOCK))
            .build()
    }

    private fun pending(action: String) = android.app.PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, KaliService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kaliterm:session")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Sesión Kali", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "kali_session"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.genesis.kaliterm.STOP"
        const val ACTION_WAKELOCK = "com.genesis.kaliterm.WAKELOCK"

        fun start(ctx: Context) {
            val i = Intent(ctx, KaliService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}

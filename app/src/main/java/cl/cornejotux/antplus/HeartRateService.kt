package cl.cornejotux.antplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Servicio persistente. La lectura ANT+ se conectará aquí mediante ANT Radio Service. */
class HeartRateService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("freerun", "FreeRUN", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "freerun")
            .setContentTitle("FreeRUN activo")
            .setContentText("Buscando monitor cardíaco")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

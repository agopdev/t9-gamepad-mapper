package com.t9mapper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.t9mapper.data.db.AppDatabase

class T9GamepadApp : Application() {

    /** Base de datos Room — accesible desde toda la app */
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_LOW = no suena, no aparece como heads-up,
                // pero sí aparece en la bandeja de notificaciones al bajar.
                // El ícono en la barra de estado se puede ocultar con
                // setShowBadge(false) y el flag de la notificación.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "t9gamepad_service"
        const val NOTIFICATION_ID = 1001
    }
}

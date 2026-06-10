package com.t9mapper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE)
        val startOnBoot = prefs.getBoolean("start_on_boot", false)

        if (!startOnBoot) {
            Log.d("BootReceiver", "Inicio en boot desactivado, omitiendo")
            return
        }

        Log.d("BootReceiver", "Iniciando servicio al arrancar el sistema")
        val serviceIntent = Intent(context, GamepadService::class.java).apply {
            action = GamepadService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

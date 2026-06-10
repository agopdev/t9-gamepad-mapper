package com.t9mapper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.t9mapper.T9GamepadApp
import com.t9mapper.service.GamepadService
import kotlinx.coroutines.*

class AppDetectionService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastPackage = ""

    companion object {
        private const val TAG = "AppDetectionService"
        @Volatile var instance: AppDetectionService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Servicio de accesibilidad conectado")
    }

    // Paquetes del sistema a ignorar — no son "apps" reales
    private val IGNORED_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "com.android.launcher",
        "android",
        "com.duoqin.inputmethod",
        "com.meditaide.old.t9.keyboard"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Ignorar paquetes del sistema que no son apps reales
        if (pkg in IGNORED_PACKAGES) return
        if (pkg == lastPackage) return
        lastPackage = pkg

        Log.d(TAG, "App en primer plano: $pkg")

        val prefs = getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE)
        val automationEnabled = prefs.getBoolean("automation_enabled", false)

        scope.launch {
            try {
                val db = (application as T9GamepadApp).database
                if (automationEnabled) {
                    val assignment = db.appProfileAssignmentDao().getAssignmentForPackage(pkg)
                    if (assignment != null && assignment.isEnabled) {
                        sendToGamepadService(GamepadService.ACTION_SET_PROFILE, assignment.profileId)
                    } else {
                        sendToGamepadService(GamepadService.ACTION_TRANSPARENT)
                    }
                } else {
                    val defaultProfile = db.profileDao().getDefaultProfile()
                    if (defaultProfile != null) {
                        sendToGamepadService(GamepadService.ACTION_SET_PROFILE, defaultProfile.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en onAccessibilityEvent: $e")
            }
        }
    }

    private fun sendToGamepadService(action: String, profileId: Long? = null) {
        val intent = Intent(this, GamepadService::class.java).apply {
            this.action = action
            profileId?.let { putExtra(GamepadService.EXTRA_PROFILE_ID, it) }
        }
        startService(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val service = GamepadService.instance ?: return false
        val consumed = service.onKeyEvent(event.keyCode, event.action)
        if (consumed) Log.d(TAG, "Tecla ${event.keyCode} interceptada y enviada al gamepad")
        return consumed
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        instance = null
        Log.d(TAG, "Servicio destruido")
    }
}

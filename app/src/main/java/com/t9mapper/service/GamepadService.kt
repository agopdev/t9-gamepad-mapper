package com.t9mapper.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.t9mapper.R
import com.t9mapper.T9GamepadApp
import com.t9mapper.data.db.AppDatabase
import com.t9mapper.data.model.KeyMapping
import com.t9mapper.data.model.MappingType
import com.t9mapper.data.model.Profile
import com.t9mapper.ui.MainActivity
import kotlinx.coroutines.*

class GamepadService : Service() {

    // ──────────────────────────────────────────────
    // Estado del servicio
    // ──────────────────────────────────────────────

    enum class State { STOPPED, RUNNING, PAUSED }

    private var state = State.STOPPED
    private var uinputFd = -1
    private var activeProfile: Profile? = null
    private var currentMappings: List<KeyMapping> = emptyList()

    /**
     * Modo transparente: el servicio corre pero NO intercepta ninguna tecla.
     * Se activa cuando automatizacion=ON y la app actual no tiene perfil asignado.
     */
    var transparentMode: Boolean = false
        private set

    // Modo ramp: valores acumulados por dirección
    private var rampX = 0
    private var rampY = 0
    private var rampJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    // ──────────────────────────────────────────────
    // Intent actions (usadas desde la notificación y la UI)
    // ──────────────────────────────────────────────

    companion object {
        const val ACTION_START        = "com.t9mapper.START"
        const val ACTION_PAUSE        = "com.t9mapper.PAUSE"
        const val ACTION_RESUME       = "com.t9mapper.RESUME"
        const val ACTION_STOP         = "com.t9mapper.STOP"
        const val ACTION_SET_PROFILE  = "com.t9mapper.SET_PROFILE"
        const val ACTION_TRANSPARENT  = "com.t9mapper.TRANSPARENT"   // modo teclado fábrica
        const val EXTRA_PROFILE_ID    = "profile_id"

        /** Referencia estática al servicio activo (para la UI) */
        @Volatile var instance: GamepadService? = null
            private set

        fun currentState(): State = instance?.state ?: State.STOPPED
        fun currentProfile(): Profile? = instance?.activeProfile
        fun isTransparent(): Boolean = instance?.transparentMode ?: false
    }

    // ──────────────────────────────────────────────
    // Ciclo de vida del Service
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = (application as T9GamepadApp).database
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START       -> handleStart()
            ACTION_PAUSE       -> handlePause()
            ACTION_RESUME      -> handleResume()
            ACTION_STOP        -> handleStop()
            ACTION_TRANSPARENT -> handleTransparent()
            ACTION_SET_PROFILE -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (profileId > 0) switchProfile(profileId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        instance = null
    }

    // ──────────────────────────────────────────────
    // Acciones del servicio
    // ──────────────────────────────────────────────

    private fun handleStart() {
        if (state == State.RUNNING) return

        state = State.RUNNING
        startForeground(T9GamepadApp.NOTIFICATION_ID, buildNotification())

        serviceScope.launch(Dispatchers.IO) {
            // Reparar el AccessibilityService si está caído
            repairAccessibilityService()

            val opened = requestRootAndOpenUinput()
            if (!opened) {
                withContext(Dispatchers.Main) {
                    broadcastError("No se pudo acceder a /dev/uinput. ¿Root activo?")
                    state = State.STOPPED
                    updateNotification()
                    stopSelf()
                }
                return@launch
            }

            val prefs = getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE)
            val automationEnabled = prefs.getBoolean("automation_enabled", false)
            if (!automationEnabled) {
                val defaultProfile = db.profileDao().getDefaultProfile()
                if (defaultProfile != null) loadProfile(defaultProfile)
            }
        }
    }

    /**
     * Si el AccessibilityService está en Crashed services, lo reactiva via root.
     * Usa la API de settings para desactivarlo y reactivarlo.
     */
    private fun repairAccessibilityService() {
        try {
            val result = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "dumpsys accessibility | grep -q 'com.t9mapper/com.t9mapper.automation.AppDetectionService' && " +
                "dumpsys accessibility | grep Crashed | grep -q 't9mapper' && " +
                "settings put secure enabled_accessibility_services \$(settings get secure enabled_accessibility_services) && " +
                "echo repaired || echo ok"
            ))
            val output = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            Log.d("GamepadService", "AccessibilityService check: $output")

            // Método más confiable: siempre refrescar la lista de servicios habilitados
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "SVCS=\$(settings get secure enabled_accessibility_services); " +
                "settings put secure enabled_accessibility_services \"\$SVCS\""
            ))
            proc.waitFor()
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.e("GamepadService", "repairAccessibilityService error: $e")
        }
    }

    private fun handlePause() {
        if (state != State.RUNNING) return
        state = State.PAUSED
        GamepadNative.resetAxes(uinputFd)
        stopRamp()
        updateNotification()
    }

    private fun handleResume() {
        if (state != State.PAUSED) return
        state = State.RUNNING
        updateNotification()
    }

    private fun handleStop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        cleanup()
        stopSelf()
    }

    /**
     * Modo transparente: la app actual no tiene perfil asignado y la automatización
     * está encendida. El servicio sigue corriendo (dispositivo uinput activo) pero
     * no intercepta ninguna tecla — el teclado funciona exactamente como de fábrica.
     */
    private fun handleTransparent() {
        transparentMode = true
        activeProfile = null
        currentMappings = emptyList()
        GamepadNative.resetAxes(uinputFd)
        stopRamp()
        updateNotification()
    }

    // ──────────────────────────────────────────────
    // Root + uinput
    // ──────────────────────────────────────────────

    /**
     * Copia el binario uinput_helper desde los assets de la app
     * a un directorio ejecutable en el almacenamiento privado.
     */
    private fun installHelper(): String? {
        return try {
            val helperFile = java.io.File(filesDir, "uinput_helper")

            // Siempre reinstalar — evita el problema de "deleted" cuando
            // el proceso anterior sigue corriendo con el binario viejo
            if (helperFile.exists()) helperFile.delete()

            // Android en algunos ROMs no extrae las .so a disco —
            // las sirve directamente desde el APK. Leemos el APK como ZIP.
            val apkPath = applicationInfo.sourceDir
            val zipFile = java.util.zip.ZipFile(apkPath)

            // Buscar libuinput_helper.so para la ABI correcta
            val abiCandidates = listOf(
                "lib/arm64-v8a/libuinput_helper.so",
                "lib/armeabi-v7a/libuinput_helper.so",
                "lib/x86_64/libuinput_helper.so"
            )

            var entry: java.util.zip.ZipEntry? = null
            for (candidate in abiCandidates) {
                entry = zipFile.getEntry(candidate)
                if (entry != null) {
                    Log.d("GamepadService", "Helper encontrado en APK: $candidate")
                    break
                }
            }

            if (entry == null) {
                Log.e("GamepadService", "libuinput_helper.so no encontrado en APK: $apkPath")
                zipFile.close()
                return null
            }

            // Extraer al filesDir
            zipFile.getInputStream(entry).use { input ->
                helperFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            zipFile.close()

            helperFile.setExecutable(true, false)
            helperFile.setReadable(true, false)
            Log.d("GamepadService", "Helper extraído: ${helperFile.absolutePath} (${helperFile.length()} bytes)")
            helperFile.absolutePath

        } catch (e: Exception) {
            Log.e("GamepadService", "installHelper error: $e")
            null
        }
    }

    private fun requestRootAndOpenUinput(): Boolean {
        return try {
            // Matar cualquier instancia anterior del helper
            val killResult = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "pkill -f uinput_helper 2>/dev/null; sleep 0.5; echo done"))
            killResult.waitFor()
            Thread.sleep(600)
            Log.d("GamepadService", "Instancias anteriores del helper terminadas")

            val helperPath = installHelper() ?: run {
                Log.e("GamepadService", "No se pudo instalar el helper")
                return false
            }

            // Lanzar helper daemon como root
            // El helper crea el gamepad virtual y queda escuchando en TCP 19999
            Log.d("GamepadService", "Lanzando helper daemon: $helperPath")
            val process = ProcessBuilder("su", "-c", helperPath)
                .redirectErrorStream(true)
                .start()

            // Log del helper en thread separado
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d("GamepadService", "helper> $line")
                    }
                } catch (e: Exception) { /* ignorar */ }
            }.also { it.isDaemon = true }.start()

            // Esperar a que el helper esté listo y conectar
            Log.d("GamepadService", "Conectando al helper daemon...")
            var sock = -1
            for (attempt in 1..50) {
                Thread.sleep(100)
                sock = GamepadNative.connectAndReceiveFd("")
                if (sock > 0) {
                    Log.d("GamepadService", "Conectado al helper en intento $attempt, sock=$sock")
                    break
                }
            }

            if (sock > 0) {
                uinputFd = sock
                return true
            }

            Log.e("GamepadService", "No se pudo conectar al helper después de 5 segundos")
            process.destroy()
            false
        } catch (e: Exception) {
            Log.e("GamepadService", "requestRootAndOpenUinput error: $e")
            false
        }
    }

    private fun cleanup() {
        state = State.STOPPED
        stopRamp()
        if (uinputFd > 0) {
            GamepadNative.resetAxes(uinputFd)
            GamepadNative.destroyDevice(uinputFd)
            uinputFd = -1
        }
        serviceScope.cancel()
    }

    // ──────────────────────────────────────────────
    // Gestión de perfiles
    // ──────────────────────────────────────────────

    fun switchProfile(profileId: Long) {
        serviceScope.launch {
            val profile = db.profileDao().getProfileById(profileId) ?: return@launch
            loadProfile(profile)
        }
    }

    private suspend fun loadProfile(profile: Profile) {
        transparentMode = false
        activeProfile = profile
        currentMappings = db.keyMappingDao().getMappingsForProfileSync(profile.id)
        GamepadNative.resetAxes(uinputFd)
        stopRamp()

        // Enviar mappings al helper para que pueda interceptar teclas directamente
        sendMappingsToHelper(currentMappings)

        withContext(Dispatchers.Main) {
            updateNotification()
        }
    }

    private fun sendMappingsToHelper(mappings: List<KeyMapping>) {
        if (uinputFd < 0 || mappings.isEmpty()) return
        try {
            val validMappings = mappings.take(64)
            // Protocolo: [0xFE][n][kc_lo][kc_hi][btn_lo][btn_hi][type][axis_hi]
            val buf = ByteArray(2 + validMappings.size * 7)
            buf[0] = 0xFE.toByte()
            buf[1] = validMappings.size.toByte()
            validMappings.forEachIndexed { i, m ->
                val typeCode = when (m.mappingType) {
                    com.t9mapper.data.model.MappingType.BUTTON      -> 1
                    com.t9mapper.data.model.MappingType.DPAD_ANALOG -> 2
                    com.t9mapper.data.model.MappingType.DPAD_HAT    -> 3
                    com.t9mapper.data.model.MappingType.AXIS        -> 4
                }
                val base = 2 + i * 7
                buf[base]     = (m.keyCode and 0xFF).toByte()
                buf[base + 1] = ((m.keyCode shr 8) and 0xFF).toByte()
                buf[base + 2] = (m.gamepadCode and 0xFF).toByte()
                buf[base + 3] = ((m.gamepadCode shr 8) and 0xFF).toByte()
                buf[base + 4] = typeCode.toByte()
                buf[base + 5] = (m.axisValue and 0xFF).toByte()
                buf[base + 6] = ((m.axisValue shr 8) and 0xFF).toByte()
            }
            GamepadNative.sendRawToSocket(uinputFd, buf)
            Log.d("GamepadService", "Mappings enviados al helper: ${validMappings.size}")
        } catch (e: Exception) {
            Log.e("GamepadService", "Error enviando mappings: $e")
        }
    }

    // ──────────────────────────────────────────────
    // Procesamiento de teclas
    //
    // Llamado desde AppDetectionService / el AccessibilityService
    // que intercepta los KeyEvents antes de que lleguen al sistema.
    // ──────────────────────────────────────────────

    fun onKeyEvent(keyCode: Int, action: Int): Boolean {
        if (state != State.RUNNING || uinputFd < 0) {
            Log.d("GamepadService", "onKeyEvent ignorado: state=$state fd=$uinputFd")
            return false
        }

        if (transparentMode) return false

        val pressed = (action == KeyEvent.ACTION_DOWN)
        val mapping = currentMappings.find { it.keyCode == keyCode && it.isEnabled }

        if (mapping == null) {
            Log.d("GamepadService", "onKeyEvent keyCode=$keyCode sin mapeo (mappings=${currentMappings.size})")
            return false
        }

        Log.d("GamepadService", "onKeyEvent keyCode=$keyCode pressed=$pressed type=${mapping.mappingType}")

        val profile = activeProfile ?: return false

        when (mapping.mappingType) {
            MappingType.BUTTON -> {
                GamepadNative.sendButton(uinputFd, mapping.gamepadCode, pressed)
            }

            MappingType.DPAD_ANALOG -> {
                val direction = keyCodeToDPadDirection(keyCode)
                if (direction == DPadDirection.NONE) return false

                when (AnalogMode.fromCode(profile.analogMode)) {
                    AnalogMode.FIXED -> {
                        GamepadNative.sendDPadAsAnalog(
                            uinputFd, direction, pressed, AnalogMode.FIXED.code, 0
                        )
                    }
                    AnalogMode.RAMP -> {
                        handleRampDPad(direction, pressed, profile.rampStep)
                    }
                }
            }

            MappingType.DPAD_HAT -> {
                val direction = keyCodeToDPadDirection(keyCode)
                GamepadNative.sendDPadHat(uinputFd, direction, pressed)
            }

            MappingType.AXIS -> {
                val value = if (pressed) mapping.axisValue else 0
                GamepadNative.sendAxis(uinputFd, mapping.gamepadCode, value)
            }
        }

        return true // Consumimos el evento (no llega al sistema)
    }

    // ──────────────────────────────────────────────
    // Ramp D-Pad — corutina que incrementa el valor
    // gradualmente mientras se mantiene presionada la tecla
    // ──────────────────────────────────────────────

    // Estado de teclas D-Pad presionadas
    private var dpadUp    = false
    private var dpadDown  = false
    private var dpadLeft  = false
    private var dpadRight = false

    private fun handleRampDPad(direction: Int, pressed: Boolean, rampStep: Int) {
        when (direction) {
            DPadDirection.UP    -> dpadUp    = pressed
            DPadDirection.DOWN  -> dpadDown  = pressed
            DPadDirection.LEFT  -> dpadLeft  = pressed
            DPadDirection.RIGHT -> dpadRight = pressed
        }

        if (!dpadUp && !dpadDown && !dpadLeft && !dpadRight) {
            // Todas soltadas → resetear
            rampX = 0; rampY = 0
            GamepadNative.sendDPadAsAnalog(uinputFd, DPadDirection.NONE, false, AnalogMode.RAMP.code, 0)
            stopRamp()
            return
        }

        // Arrancar corutina de ramp si no está corriendo
        if (rampJob?.isActive != true) {
            rampJob = serviceScope.launch {
                while (isActive && (dpadUp || dpadDown || dpadLeft || dpadRight)) {
                    // Calcular dirección resultante
                    val targetX = when {
                        dpadRight -> AxisCode.AXIS_MAX
                        dpadLeft  -> AxisCode.AXIS_MIN
                        else      -> 0
                    }
                    val targetY = when {
                        dpadDown -> AxisCode.AXIS_MAX
                        dpadUp   -> AxisCode.AXIS_MIN
                        else     -> 0
                    }

                    // Incrementar hacia el target
                    rampX = rampTowards(rampX, targetX, rampStep)
                    rampY = rampTowards(rampY, targetY, rampStep)

                    // Determinar dirección dominante para el native call
                    val dir = currentDPadDirection()
                    GamepadNative.sendDPadAsAnalog(
                        uinputFd, dir, true,
                        AnalogMode.RAMP.code,
                        maxOf(kotlin.math.abs(rampX), kotlin.math.abs(rampY))
                    )

                    delay(16L) // ~60 fps
                }
            }
        }
    }

    private fun rampTowards(current: Int, target: Int, step: Int): Int {
        return when {
            current < target -> minOf(current + step, target)
            current > target -> maxOf(current - step, target)
            else -> current
        }
    }

    private fun currentDPadDirection(): Int {
        return when {
            dpadUp    && dpadLeft  -> DPadDirection.UP_LEFT
            dpadUp    && dpadRight -> DPadDirection.UP_RIGHT
            dpadDown  && dpadLeft  -> DPadDirection.DOWN_LEFT
            dpadDown  && dpadRight -> DPadDirection.DOWN_RIGHT
            dpadUp                 -> DPadDirection.UP
            dpadDown               -> DPadDirection.DOWN
            dpadLeft               -> DPadDirection.LEFT
            dpadRight              -> DPadDirection.RIGHT
            else                   -> DPadDirection.NONE
        }
    }

    private fun stopRamp() {
        rampJob?.cancel()
        rampJob = null
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        rampX = 0; rampY = 0
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun keyCodeToDPadDirection(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP    -> DPadDirection.UP
        KeyEvent.KEYCODE_DPAD_DOWN  -> DPadDirection.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT  -> DPadDirection.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPadDirection.RIGHT
        else                        -> DPadDirection.NONE
    }

    private fun broadcastError(msg: String) {
        val intent = Intent("com.t9mapper.ERROR").putExtra("message", msg)
        sendBroadcast(intent)
    }

    // ──────────────────────────────────────────────
    // Notificación persistente
    // ──────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profileName = when {
            transparentMode          -> "Teclado de fábrica"
            activeProfile != null    -> activeProfile!!.name
            else                     -> getString(R.string.no_profile)
        }
        val statusText = when (state) {
            State.RUNNING -> profileName
            State.PAUSED  -> getString(R.string.service_paused)
            State.STOPPED -> getString(R.string.service_stopped)
        }

        val builder = NotificationCompat.Builder(this, T9GamepadApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            // Ocultar el ícono en la barra de estado (solo aparece en la bandeja)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Acción Pausar / Reanudar
        if (state == State.RUNNING) {
            val pauseIntent = PendingIntent.getService(
                this, 1,
                Intent(this, GamepadService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_pause), pauseIntent)
        } else if (state == State.PAUSED) {
            val resumeIntent = PendingIntent.getService(
                this, 2,
                Intent(this, GamepadService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.notification_resume), resumeIntent)
        }

        // Acción Detener
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, GamepadService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_delete, getString(R.string.notification_stop), stopIntent)

        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(T9GamepadApp.NOTIFICATION_ID, buildNotification())
    }
}

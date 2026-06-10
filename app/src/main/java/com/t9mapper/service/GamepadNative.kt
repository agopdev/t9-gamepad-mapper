package com.t9mapper.service

/**
 * Wrapper Kotlin para las funciones JNI del driver nativo.
 *
 * Corresponde exactamente a los símbolos exportados en gamepad.c.
 * Todos los métodos son @JvmStatic para poder llamarse sin instancia.
 */
object GamepadNative {

    init {
        System.loadLibrary("gamepad")
    }

    // ──────────────────────────────────────────────
    // Ciclo de vida del dispositivo
    // ──────────────────────────────────────────────

    /**
     * Crea el dispositivo virtual en /dev/uinput.
     * Requiere que el proceso tenga permisos de escritura en /dev/uinput (root).
     *
     * @return File descriptor (> 0) o código de error:
     *   -2 = no se pudo abrir /dev/uinput
     *   -3 = error de configuración ioctl
     */
    @JvmStatic
    external fun createDevice(): Int

    /**
     * Destruye el dispositivo virtual y cierra el fd.
     * Llamar siempre al detener el servicio.
     */
    @JvmStatic
    external fun destroyDevice(fd: Int)

    /**
     * Conecta al helper daemon y devuelve el socket fd.
     */
    @JvmStatic
    external fun connectAndReceiveFd(socketPath: String): Int

    /**
     * Envía bytes raw al socket fd (para enviar mappings al helper).
     */
    @JvmStatic
    external fun sendRawToSocket(socketFd: Int, data: ByteArray): Int

    // ──────────────────────────────────────────────
    // Eventos de input
    // ──────────────────────────────────────────────

    /**
     * Envía un evento de botón.
     *
     * @param fd       FD del dispositivo
     * @param btnCode  Código BTN_* (usar constantes de [ButtonCode])
     * @param pressed  true = presionado, false = soltado
     */
    @JvmStatic
    external fun sendButton(fd: Int, btnCode: Int, pressed: Boolean)

    /**
     * Envía un valor de eje absoluto directamente.
     *
     * @param fd        FD del dispositivo
     * @param axisCode  Código ABS_* (usar constantes de [AxisCode])
     * @param value     Valor en rango -32767 … 32767
     */
    @JvmStatic
    external fun sendAxis(fd: Int, axisCode: Int, value: Int)

    /**
     * Envía el D-Pad convertido a ejes analógicos (Circle Pad / Left Stick).
     * Soporta modos FIXED y RAMP.
     *
     * @param fd         FD del dispositivo
     * @param direction  Dirección (ver [DPadDirection])
     * @param pressed    true = tecla presionada
     * @param mode       0 = FIXED, 1 = RAMP
     * @param rampValue  Valor acumulado del ramp (0-32767). Ignorado si mode=FIXED.
     */
    @JvmStatic
    external fun sendDPadAsAnalog(
        fd: Int,
        direction: Int,
        pressed: Boolean,
        mode: Int,
        rampValue: Int
    )

    /**
     * Envía el D-Pad como HAT switch (modo digital estándar).
     * Usar cuando el emulador tiene D-Pad separado del analógico.
     *
     * @param fd        FD del dispositivo
     * @param direction Dirección (ver [DPadDirection])
     * @param pressed   true = tecla presionada
     */
    @JvmStatic
    external fun sendDPadHat(fd: Int, direction: Int, pressed: Boolean)

    /**
     * Resetea todos los ejes a 0 (posición neutral).
     * Llamar cuando se pausa el servicio o se pierde el foco.
     */
    @JvmStatic
    external fun resetAxes(fd: Int)
}

// ──────────────────────────────────────────────
// Constantes de ejes (ABS_*)
// ──────────────────────────────────────────────
object AxisCode {
    const val ABS_X      = 0x00  // Left stick horizontal
    const val ABS_Y      = 0x01  // Left stick vertical
    const val ABS_Z      = 0x02  // Left trigger
    const val ABS_RX     = 0x03  // Right stick horizontal
    const val ABS_RY     = 0x04  // Right stick vertical
    const val ABS_RZ     = 0x05  // Right trigger
    const val ABS_HAT0X  = 0x10  // D-Pad horizontal (HAT)
    const val ABS_HAT0Y  = 0x11  // D-Pad vertical   (HAT)

    const val AXIS_MIN   = -32767
    const val AXIS_MAX   =  32767
    const val AXIS_NEUTRAL = 0
}

// ──────────────────────────────────────────────
// Constantes de botones (BTN_*)
// ──────────────────────────────────────────────
object ButtonCode {
    const val BTN_A       = 0x130
    const val BTN_B       = 0x131
    const val BTN_X       = 0x133
    const val BTN_Y       = 0x134
    const val BTN_TL      = 0x136  // L
    const val BTN_TR      = 0x137  // R
    const val BTN_TL2     = 0x138  // ZL
    const val BTN_TR2     = 0x139  // ZR
    const val BTN_SELECT  = 0x13A
    const val BTN_START   = 0x13B
    const val BTN_MODE    = 0x13C  // Home / Guide
    const val BTN_THUMBL  = 0x13D  // LS
    const val BTN_THUMBR  = 0x13E  // RS
    const val BTN_DPAD_UP    = 0x220
    const val BTN_DPAD_DOWN  = 0x221
    const val BTN_DPAD_LEFT  = 0x222
    const val BTN_DPAD_RIGHT = 0x223
}

// ──────────────────────────────────────────────
// Direcciones del D-Pad
// ──────────────────────────────────────────────
object DPadDirection {
    const val NONE        = 0
    const val UP          = 1
    const val DOWN        = 2
    const val LEFT        = 3
    const val RIGHT       = 4
    const val UP_LEFT     = 5
    const val UP_RIGHT    = 6
    const val DOWN_LEFT   = 7
    const val DOWN_RIGHT  = 8
}

// ──────────────────────────────────────────────
// Modos analógicos del D-Pad
// ──────────────────────────────────────────────
enum class AnalogMode(val code: Int) {
    FIXED(0),  // Valor máximo inmediato
    RAMP(1);   // Aumenta gradualmente al mantener

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: FIXED
    }
}

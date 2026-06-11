package com.t9mapper.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.t9mapper.service.AnalogMode
import kotlinx.serialization.Serializable

// ──────────────────────────────────────────────
// Perfil de mapeo
// ──────────────────────────────────────────────

@Serializable
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Nombre visible del perfil (ej: "Azahar 3DS", "RetroArch SNES") */
    val name: String,

    /** Si es el perfil predeterminado cuando la automatización está apagada */
    val isDefault: Boolean = false,

    /** Descripción opcional */
    val description: String = "",

    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis(),

    /** Modo analógico del D-Pad: 0=FIXED 1=RAMP */
    val analogMode: Int = AnalogMode.FIXED.code,

    /**
     * Velocidad del ramp (solo aplica si analogMode=RAMP).
     * Pasos por tick (16ms). Rango recomendado: 512–8192.
     * Default: 4096 (~0.25 segundos para llegar al máximo)
     */
    val rampStep: Int = 4096,

    /**
     * Si está activo (el servicio puede usarlo).
     * Útil para desactivar perfiles sin borrarlos.
     */
    val isActive: Boolean = true,

    /**
     * NUEVO CAMPO: Tipo de dispositivo a emular
     * 0 = Teclado Default (Fábrica)
     * 1 = Mando Xbox 360
     * 2 = Teclado PC (USB/Bluetooth)
     */
    val deviceType: Int = 1
)

// ──────────────────────────────────────────────
// Mapeo de tecla
// ──────────────────────────────────────────────

/**
 * Tipo de acción que genera una tecla mapeada.
 */
@Serializable
enum class MappingType {
    /** Presionar/soltar un botón del gamepad (BTN_A, BTN_B, etc.) */
    BUTTON,

    /** El D-Pad completo emitiendo ejes analógicos */
    DPAD_ANALOG,

    /** El D-Pad emitiendo HAT switch digital */
    DPAD_HAT,

    /** Eje absoluto con valor fijo (triggers, etc.) */
    AXIS
}

@Serializable
@Entity(
    tableName = "key_mappings",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["profileId", "keyCode"], unique = true)
    ]
)
data class KeyMapping(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Perfil al que pertenece este mapeo */
    val profileId: Long,

    /**
     * Código de la tecla física del DuoQin / teclado T9.
     * Usar los valores de android.view.KeyEvent (KEYCODE_*)
     *
     * Ejemplos DuoQin F22 Pro:
     *   KEYCODE_DPAD_UP    = 19
     *   KEYCODE_DPAD_DOWN  = 20
     *   KEYCODE_DPAD_LEFT  = 21
     *   KEYCODE_DPAD_RIGHT = 22
     *   KEYCODE_DPAD_CENTER = 23
     *   KEYCODE_0..9 = 7..16
     *   KEYCODE_STAR = 17
     *   KEYCODE_POUND = 18
     *   KEYCODE_CALL = 5
     *   KEYCODE_ENDCALL = 6
     *   KEYCODE_BACK = 4
     *   KEYCODE_MENU = 82
     *   KEYCODE_VOLUME_UP = 24
     *   KEYCODE_VOLUME_DOWN = 25
     */
    val keyCode: Int,

    /** Nombre legible de la tecla (ej: "↑ D-Pad", "Tecla 5", "Estrella *") */
    val keyLabel: String,

    /** Tipo de acción generada */
    val mappingType: MappingType,

    /**
     * Código del botón/eje gamepad destino.
     * - Si mappingType = BUTTON: código BTN_* (ver ButtonCode)
     * - Si mappingType = AXIS:   código ABS_* (ver AxisCode)
     * - Si mappingType = DPAD_*: ignorado (el D-Pad se maneja en conjunto)
     */
    val gamepadCode: Int = 0,

    /**
     * Valor para ejes (solo aplica si mappingType = AXIS).
     * Rango: -32767 … 32767
     */
    val axisValue: Int = 32767,

    /** Si esta tecla está habilitada en este perfil */
    val isEnabled: Boolean = true
)

// ──────────────────────────────────────────────
// Asignación automática app → perfil
// ──────────────────────────────────────────────

@Serializable
@Entity(
    tableName = "app_profile_assignments",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class AppProfileAssignment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Package name de la app (ej: "org.citra_emu.citra", "com.azahar.emulator") */
    val packageName: String,

    /** Nombre display de la app (guardado para no tener que consultar PackageManager) */
    val appName: String,

    /** ID del perfil asignado */
    val profileId: Long,

    /** Si la asignación está activa */
    val isEnabled: Boolean = true
)

// ──────────────────────────────────────────────
// Claves físicas predefinidas del DuoQin F22 Pro
// para facilitar la UI de mapeo
// ──────────────────────────────────────────────

data class PhysicalKey(
    val keyCode: Int,
    val label: String,
    val symbol: String    // Carácter o ícono corto para mostrar en la UI
)

object DuoQinKeys {
    // Keycodes del kernel Linux (los que llegan a /dev/input/eventX)
    // Verificados en DuoQin F22 Pro con getevent
    val ALL_KEYS = listOf(
        PhysicalKey(103, "D-Pad Arriba",    "↑"),   // KEY_UP
        PhysicalKey(108, "D-Pad Abajo",     "↓"),   // KEY_DOWN
        PhysicalKey(105, "D-Pad Izquierda", "←"),   // KEY_LEFT
        PhysicalKey(106, "D-Pad Derecha",   "→"),   // KEY_RIGHT
        PhysicalKey(353, "D-Pad Centro",    "OK"),  // KEY_OK (0x161)
        PhysicalKey(11,  "Tecla 0",         "0"),   // KEY_0
        PhysicalKey(2,   "Tecla 1",         "1"),   // KEY_1
        PhysicalKey(3,   "Tecla 2",         "2"),   // KEY_2
        PhysicalKey(4,   "Tecla 3",         "3"),   // KEY_3
        PhysicalKey(5,   "Tecla 4",         "4"),   // KEY_4
        PhysicalKey(6,   "Tecla 5",         "5"),   // KEY_5
        PhysicalKey(7,   "Tecla 6",         "6"),   // KEY_6
        PhysicalKey(8,   "Tecla 7",         "7"),   // KEY_7
        PhysicalKey(9,   "Tecla 8",         "8"),   // KEY_8
        PhysicalKey(10,  "Tecla 9",         "9"),   // KEY_9
        PhysicalKey(522, "Estrella *",      "*"),   // KEY_KPASTERISK
        PhysicalKey(523, "Numeral #",       "#"),   // KEY_NUMERIC_POUND
        PhysicalKey(169, "Llamar",          "📞"),  // KEY_PHONE (0xa9)
        PhysicalKey(116, "Colgar/Power",    "📵"),  // KEY_POWER (0x74)
        PhysicalKey(158, "Atrás",           "⬅"),  // KEY_BACK (0x9e)
        PhysicalKey(139, "Menú",            "☰"),  // KEY_MENU
        PhysicalKey(115, "Volumen +",       "🔊"),  // KEY_VOLUMEUP
        PhysicalKey(114, "Volumen -",       "🔉"),  // KEY_VOLUMEDOWN
    )
}

package com.t9mapper.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.t9mapper.data.model.Profile
import com.t9mapper.service.AnalogMode
import com.t9mapper.service.GamepadService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun isAccessibilityOn(): Boolean {
        val expected = "${context.packageName}/${context.packageName}.automation.AppDetectionService"
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    var serviceState by remember { mutableStateOf(GamepadService.currentState()) }
    var activeProfile by remember { mutableStateOf<Profile?>(GamepadService.currentProfile()) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityOn()) }
    var transparentMode by remember { mutableStateOf(GamepadService.isTransparent()) }

    val prefs = remember { context.getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE) }
    var automationEnabled by remember {
        mutableStateOf(prefs.getBoolean("automation_enabled", false))
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityOn()
                serviceState         = GamepadService.currentState()
                activeProfile        = GamepadService.currentProfile()
                transparentMode      = GamepadService.isTransparent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            serviceState         = GamepadService.currentState()
            activeProfile        = GamepadService.currentProfile()
            accessibilityEnabled = isAccessibilityOn()
            transparentMode      = GamepadService.isTransparent()
            automationEnabled    = prefs.getBoolean("automation_enabled", false)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Título ──
        Text(
            text = "T9 Gamepad Mapper",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // ── Botón de encendido ──
        PowerButton(
            state = serviceState,
            onClick = {
                scope.launch {
                    when (serviceState) {
                        GamepadService.State.STOPPED -> startService(context)
                        GamepadService.State.RUNNING -> pauseService(context)
                        GamepadService.State.PAUSED  -> resumeService(context)
                    }
                }
            },
            onLongClick = {
                if (serviceState != GamepadService.State.STOPPED) {
                    stopService(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Tarjeta de estado ──
        StatusCard(
            serviceState      = serviceState,
            activeProfile     = activeProfile,
            automationEnabled = automationEnabled,
            transparentMode   = transparentMode,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Advertencia AccessibilityService ──
        if (!accessibilityEnabled) {
            AccessibilityWarning(context)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Switch inicio en boot ──
        var startOnBoot by remember {
            mutableStateOf(prefs.getBoolean("start_on_boot", false))
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Iniciar al encender",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Activa el servicio automáticamente al reiniciar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = startOnBoot,
                    onCheckedChange = { enabled ->
                        startOnBoot = enabled
                        prefs.edit().putBoolean("start_on_boot", enabled).apply()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Texto de ayuda ──
        Text(
            text = "Mantén presionado el botón para detener el servicio por completo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}

// ──────────────────────────────────────────────
// Botón de encendido circular
// ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PowerButton(
    state: GamepadService.State,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = when (state) {
            GamepadService.State.RUNNING -> Color(0xFF00C853)
            GamepadService.State.PAUSED  -> Color(0xFFFF9800)
            GamepadService.State.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(400),
        label = "powerBtnColor"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            GamepadService.State.RUNNING -> Color(0xFF00C853).copy(alpha = 0.3f)
            GamepadService.State.PAUSED  -> Color(0xFFFF9800).copy(alpha = 0.3f)
            GamepadService.State.STOPPED -> Color.Transparent
        },
        animationSpec = tween(400),
        label = "ringColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(ringColor)
        )
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .border(2.dp, buttonColor.copy(alpha = 0.6f), CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Encendido",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = when (state) {
                        GamepadService.State.RUNNING -> "ON"
                        GamepadService.State.PAUSED  -> "PAUSA"
                        GamepadService.State.STOPPED -> "OFF"
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Tarjeta de estado
// ──────────────────────────────────────────────

@Composable
private fun StatusCard(
    serviceState: GamepadService.State,
    activeProfile: Profile?,
    automationEnabled: Boolean,
    transparentMode: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            StatusRow(
                icon  = Icons.Filled.FiberManualRecord,
                iconTint = when (serviceState) {
                    GamepadService.State.RUNNING -> Color(0xFF00C853)
                    GamepadService.State.PAUSED  -> Color(0xFFFF9800)
                    GamepadService.State.STOPPED -> Color.Gray
                },
                label = "Estado",
                value = when (serviceState) {
                    GamepadService.State.RUNNING -> "Activo"
                    GamepadService.State.PAUSED  -> "Pausado"
                    GamepadService.State.STOPPED -> "Detenido"
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            StatusRow(
                icon      = Icons.Filled.Gamepad,
                iconTint  = if (transparentMode)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.primary,
                label     = "Perfil activo",
                value     = if (transparentMode)
                    "Teclado de fábrica"
                else
                    activeProfile?.name ?: "— Sin perfil —"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            StatusRow(
                icon     = Icons.Filled.Bolt,
                iconTint = if (automationEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                label    = "Modo",
                value    = if (automationEnabled) "Automático" else "Manual (perfil predeterminado)"
            )

            if (activeProfile != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                StatusRow(
                    icon     = Icons.Filled.Tune,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    label    = "D-Pad analógico",
                    value    = when (AnalogMode.fromCode(activeProfile.analogMode)) {
                        AnalogMode.FIXED -> "Fijo (máximo inmediato)"
                        AnalogMode.RAMP  -> "Gradual (ramp ${activeProfile.rampStep})"
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ──────────────────────────────────────────────
// Advertencia: AccessibilityService desactivado
// ──────────────────────────────────────────────

@Composable
private fun AccessibilityWarning(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Servicio de accesibilidad inactivo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Necesario para interceptar las teclas físicas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ) {
                Text("Activar")
            }
        }
    }
}

// ──────────────────────────────────────────────
// Helpers para controlar el servicio
// ──────────────────────────────────────────────

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent = "${context.packageName}/${context.packageName}.automation.AppDetectionService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(":").any {
        it.equals(expectedComponent, ignoreCase = true)
    }
}

private fun startService(context: Context) {
    val intent = Intent(context, GamepadService::class.java).apply {
        action = GamepadService.ACTION_START
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    context.getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("service_was_running", true).apply()
}

private fun pauseService(context: Context) {
    context.startService(
        Intent(context, GamepadService::class.java).apply { action = GamepadService.ACTION_PAUSE }
    )
}

private fun resumeService(context: Context) {
    context.startService(
        Intent(context, GamepadService::class.java).apply { action = GamepadService.ACTION_RESUME }
    )
}

private fun stopService(context: Context) {
    context.startService(
        Intent(context, GamepadService::class.java).apply { action = GamepadService.ACTION_STOP }
    )
    context.getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("service_was_running", false).apply()
}

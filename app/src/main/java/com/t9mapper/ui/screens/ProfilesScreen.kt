package com.t9mapper.ui.screens

import com.t9mapper.data.db.AppDatabase
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.t9mapper.T9GamepadApp
import com.t9mapper.data.model.*
import com.t9mapper.service.AnalogMode
import com.t9mapper.service.ButtonCode
import com.t9mapper.service.AxisCode
import com.t9mapper.service.GamepadService
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

// ══════════════════════════════════════════════
// Lista de perfiles
// ══════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(navController: NavController) {
    val context = LocalContext.current
    val db = (context.applicationContext as T9GamepadApp).database
    val scope = rememberCoroutineScope()

    val profiles by db.profileDao().getAllProfiles().collectAsStateWithLifecycle(emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }

    fun duplicateProfile(profile: Profile) {
        scope.launch {
            val clonedProfile = profile.copy(id = 0, name = "${profile.name} (Copia)")
            val newProfileId = withContext(Dispatchers.IO) {
                db.profileDao().insertProfile(clonedProfile) 
            }

            withContext(Dispatchers.IO) {
                // Usamos keyMappingDao en lugar de profileDao y leemos el valor actual del Flow con .first()
                val originalMappings = db.keyMappingDao().getMappingsForProfile(profile.id).first()
                
                val clonedMappings = originalMappings.map { mapping ->
                    // newProfileId suele ser Long al hacer un insert en Room
                    mapping.copy(id = 0, profileId = newProfileId)
                }
                
                // Insertamos uno por uno usando el método que ya tienes definido
                clonedMappings.forEach { mapping ->
                    db.keyMappingDao().insertMapping(mapping)
                }
            }
        }
    }

    var profileToExport by remember { mutableStateOf<Profile?>(null) }
    var mappingsToExport by remember { mutableStateOf<List<KeyMapping>>(emptyList()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && profileToExport != null) {
            scope.launch(Dispatchers.IO) {
                exportProfileData(context, profileToExport!!, mappingsToExport, uri)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                importProfileData(context, it, db)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Perfiles") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Importar perfil")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, "Nuevo perfil")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Sin perfiles. Toca + para crear uno.", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onEdit = { navController.navigate("profile_edit/${profile.id}") },
                        onDuplicate = { duplicateProfile(profile) },
                        onExport = {
                            scope.launch {
                                val maps = db.keyMappingDao().getMappingsForProfile(profile.id).first()
                                profileToExport = profile
                                mappingsToExport = maps
                                exportLauncher.launch("${profile.name}.json")
                            }
                        },
                        onSetDefault = {
                            scope.launch { db.profileDao().setAsDefault(profile.id) }
                        },
                        onDelete = {
                            scope.launch {
                                if (!profile.isDefault) {
                                    db.profileDao().deleteProfile(profile)
                                }
                            }
                        },
                        onActivate = {
                            context.startService(
                                Intent(context, GamepadService::class.java).apply {
                                    action = GamepadService.ACTION_SET_PROFILE
                                    putExtra(GamepadService.EXTRA_PROFILE_ID, profile.id)
                                }
                            )
                        }
                    )
                }
            }
        }

        if (showCreateDialog) {
            CreateProfileDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { n, d, type -> 
                    scope.launch { 
                        db.profileDao().insertProfile(Profile(name = n, description = d, deviceType = type)) 
                    }
                    showCreateDialog = false 
                }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isDefault)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Gamepad,
                    contentDescription = null,
                    tint = if (profile.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(0.6f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (profile.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Badge { Text("DEFAULT") }
                        }
                    }
                    if (profile.description.isNotBlank()) {
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                        )
                    }
                    Text(
                        "D-Pad: ${if (profile.analogMode == AnalogMode.FIXED.code) "Fijo" else "Gradual"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val deviceLabel = when (profile.deviceType) {
                        0 -> "Fábrica"
                        1 -> "Xbox 360"
                        2 -> "Teclado PC"
                        else -> "?"
                    }
                    Text(
                        "Dispositivo: $deviceLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                // <-- BOTÓN NUEVO AQUÍ
                IconButton(onClick = onDuplicate) {
                    Icon(
                        Icons.Filled.ContentCopy, 
                        contentDescription = "Duplicar perfil",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Más opciones"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Editar")
                    }
                    OutlinedButton(
                        onClick = onActivate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Usar")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!profile.isDefault) {
                        OutlinedButton(
                            onClick = onSetDefault,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Star, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Predeterminar")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Eliminar")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Exportar")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Int) -> Unit // <-- Añadido el Int para deviceType
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(1) } // 1 = Xbox por defecto

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(8.dp))
                Text("Tipo de Emulación:", style = MaterialTheme.typography.labelMedium)
                
                // Opción 0: Default
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedType == 0, onClick = { selectedType = 0 })
                    Text("Teclado de Fábrica (Sin modificar)", style = MaterialTheme.typography.bodyMedium)
                }
                // Opción 1: Xbox 360
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedType == 1, onClick = { selectedType = 1 })
                    Text("Mando Xbox 360", style = MaterialTheme.typography.bodyMedium)
                }
                // Opción 2: Teclado PC
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedType == 2, onClick = { selectedType = 2 })
                    Text("Teclado PC (USB/Bluetooth)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), desc.trim(), selectedType) },
                enabled = name.isNotBlank()
            ) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// ══════════════════════════════════════════════
// Editor de perfil
// ══════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: Long,
    navController: NavController
) {
    val context = LocalContext.current
    val db = (context.applicationContext as T9GamepadApp).database
    val scope = rememberCoroutineScope()

    val mappings by db.keyMappingDao().getMappingsForProfile(profileId)
        .collectAsStateWithLifecycle(emptyList())

    var profile by remember { mutableStateOf<Profile?>(null) }
    var profileName by remember { mutableStateOf("") }
    var profileDesc by remember { mutableStateOf("") }
    var analogMode by remember { mutableStateOf(AnalogMode.FIXED) }
    var rampStep by remember { mutableStateOf(4096f) }
    var deviceType by remember { mutableStateOf(1) }

    // Dialogo para mapear una tecla
    var mappingDialogKey by remember { mutableStateOf<PhysicalKey?>(null) }

    LaunchedEffect(profileId) {
        val p = db.profileDao().getProfileById(profileId) ?: return@LaunchedEffect
        profile = p
        profileName = p.name
        profileDesc = p.description
        analogMode = AnalogMode.fromCode(p.analogMode)
        rampStep = p.rampStep.toFloat()
        deviceType = p.deviceType
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar: $profileName") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            profile?.let {
                                db.profileDao().updateProfile(
                                    it.copy(
                                        name = profileName,
                                        description = profileDesc,
                                        analogMode = analogMode.code,
                                        rampStep = rampStep.toInt(),
                                        deviceType = deviceType
                                    )
                                )
                                // Si este perfil está activo en el daemon, recargarlo
                                if (GamepadService.currentProfile()?.id == it.id &&
                                    GamepadService.currentState() == GamepadService.State.RUNNING) {
                                    context.startService(
                                        Intent(context, GamepadService::class.java).apply {
                                            action = GamepadService.ACTION_RELOAD_ACTIVE_PROFILE
                                        }
                                    )
                                }
                            }
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.Filled.Save, "Guardar")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Datos del perfil ──
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Información del perfil", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = profileDesc,
                            onValueChange = { profileDesc = it },
                            label = { Text("Descripción") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Tipo de dispositivo:", style = MaterialTheme.typography.labelMedium)
                        val deviceOptions = listOf(
                            0 to "Teclado de Fábrica",
                            1 to "Mando Xbox 360",
                            2 to "Teclado PC"
                        )
                        deviceOptions.forEach { (code, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = deviceType == code,
                                    onClick = { deviceType = code }
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Configuración del D-Pad analógico ──
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("D-Pad → Analógico", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Configura cómo las teclas direccionales emulan el Circle Pad / Left Stick",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnalogMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = analogMode == mode,
                                    onClick = { analogMode = mode },
                                    label = {
                                        Text(if (mode == AnalogMode.FIXED) "Fijo" else "Gradual")
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (analogMode == AnalogMode.RAMP) {
                            Text(
                                "Velocidad del ramp: ${rampStep.toInt()} pasos/tick",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = rampStep,
                                onValueChange = { rampStep = it },
                                valueRange = 512f..8192f,
                                steps = 14,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Lento", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                                Text("Rápido", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                            }
                        }
                    }
                }
            }

            // ── Lista de teclas mapeables ──
            item {
                Text(
                    "Mapeo de teclas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Toca cualquier tecla para asignarle una acción de gamepad",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                )
            }

            items(DuoQinKeys.ALL_KEYS, key = { it.keyCode }) { physKey ->
                val currentMapping = mappings.find { it.keyCode == physKey.keyCode }
                KeyMappingRow(
                    physKey = physKey,
                    mapping = currentMapping,
                    onClick = { mappingDialogKey = physKey }
                )
            }
        }
    }

    // Diálogo de mapeo
    mappingDialogKey?.let { key ->
        val current = mappings.find { it.keyCode == key.keyCode }
        KeyMappingDialog(
            physKey = key,
            currentMapping = current,
            onDismiss = { mappingDialogKey = null },
            onSave = { newMapping ->
                scope.launch {
                    db.keyMappingDao().insertMapping(newMapping.copy(profileId = profileId))
                }
                mappingDialogKey = null
            },
            onDelete = {
                scope.launch {
                    db.keyMappingDao().deleteMappingByKey(profileId, key.keyCode)
                }
                mappingDialogKey = null
            }
        )
    }
}

@Composable
private fun KeyMappingRow(
    physKey: PhysicalKey,
    mapping: KeyMapping?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tecla física
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(physKey.symbol, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(physKey.label, style = MaterialTheme.typography.bodyMedium)
                if (mapping != null) {
                    Text(
                        mappingDescription(mapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "Sin asignar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(0.4f)
            )
        }
    }
}

private fun mappingDescription(m: KeyMapping): String = when (m.mappingType) {
    MappingType.BUTTON     -> "Botón: ${buttonName(m.gamepadCode)}"
    MappingType.DPAD_ANALOG -> "D-Pad → ${if (m.gamepadCode == 1) "C-Stick / RS" else "Circle Pad / LS"}"
    MappingType.DPAD_HAT   -> "D-Pad → HAT digital"
    MappingType.AXIS       -> "Eje: ${axisName(m.gamepadCode)} = ${m.axisValue}"
}

private fun buttonName(code: Int): String = when (code) {
    0x130 -> "A";      0x131 -> "B"
    0x133 -> "X";      0x134 -> "Y"
    0x136 -> "LB";     0x137 -> "RB"
    0x138 -> "LT";     0x139 -> "RT"
    0x13A -> "Select"; 0x13B -> "Start"; 0x13C -> "Home"
    0x13D -> "LS";     0x13E -> "RS"
    0x220 -> "D↑";     0x221 -> "D↓"
    0x222 -> "D←";     0x223 -> "D→"
    else  -> "0x${code.toString(16)}"
}

private fun axisName(code: Int): String = when (code) {
    0x00 -> "ABS_X (Left H)"; 0x01 -> "ABS_Y (Left V)"
    0x02 -> "ABS_Z (L Trigger)"; 0x03 -> "ABS_RX (Right H)"
    0x04 -> "ABS_RY (Right V)"; 0x05 -> "ABS_RZ (R Trigger)"
    else -> "0x${code.toString(16)}"
}

// ──────────────────────────────────────────────
// Diálogo de mapeo de tecla
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyMappingDialog(
    physKey: PhysicalKey,
    currentMapping: KeyMapping?,
    onDismiss: () -> Unit,
    onSave: (KeyMapping) -> Unit,
    onDelete: () -> Unit
) {
    val isDPad = physKey.keyCode in listOf(103, 108, 105, 106) // UP/DOWN/LEFT/RIGHT keycodes del kernel

    var selectedType by remember {
        mutableStateOf(currentMapping?.mappingType ?: if (isDPad) MappingType.DPAD_ANALOG else MappingType.BUTTON)
    }
    var selectedBtnCode by remember { mutableStateOf(currentMapping?.gamepadCode ?: ButtonCode.BTN_A) }
    var selectedAxisCode by remember { mutableStateOf(currentMapping?.gamepadCode ?: AxisCode.ABS_X) }
    var axisValue by remember { mutableStateOf(currentMapping?.axisValue?.toFloat() ?: 32767f) }
    var analogStick by remember { mutableStateOf(if (currentMapping?.mappingType == MappingType.DPAD_ANALOG) currentMapping.gamepadCode else 0) }

    val buttonOptions = listOf(
        ButtonCode.BTN_A      to "A",
        ButtonCode.BTN_B      to "B",
        ButtonCode.BTN_X      to "X",
        ButtonCode.BTN_Y      to "Y",
        ButtonCode.BTN_TL     to "LB",
        ButtonCode.BTN_TR     to "RB",
        ButtonCode.BTN_TL2    to "LT",
        ButtonCode.BTN_TR2    to "RT",
        ButtonCode.BTN_THUMBL to "LS",
        ButtonCode.BTN_THUMBR to "RS",
        ButtonCode.BTN_SELECT to "Select",
        ButtonCode.BTN_START  to "Start",
        ButtonCode.BTN_MODE   to "Home",
        ButtonCode.BTN_DPAD_UP    to "D↑",
        ButtonCode.BTN_DPAD_DOWN  to "D↓",
        ButtonCode.BTN_DPAD_LEFT  to "D←",
        ButtonCode.BTN_DPAD_RIGHT to "D→",
    )

    val axisOptions = listOf(
        AxisCode.ABS_X to "Left H (ABS_X)",
        AxisCode.ABS_Y to "Left V (ABS_Y)",
        AxisCode.ABS_Z to "L Trigger (ABS_Z)",
        AxisCode.ABS_RX to "Right H (ABS_RX)",
        AxisCode.ABS_RY to "Right V (ABS_RY)",
        AxisCode.ABS_RZ to "R Trigger (ABS_RZ)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mapear: ${physKey.label}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // Tipo de mapeo
                Text("Tipo de acción:", style = MaterialTheme.typography.labelMedium)

                val types = buildList {
                    if (isDPad) {
                        add(MappingType.DPAD_ANALOG to "Analógico (Circle Pad)")
                        add(MappingType.DPAD_HAT to "D-Pad digital (HAT)")
                    }
                    add(MappingType.BUTTON to "Botón")
                    add(MappingType.AXIS to "Eje absoluto")
                }

                types.forEach { (type, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Opciones según tipo
                when (selectedType) {
                    MappingType.BUTTON -> {
                        HorizontalDivider()
                        Text("Botón del gamepad:", style = MaterialTheme.typography.labelMedium)
                        buttonOptions.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { (code, name) ->
                                    FilterChip(
                                        selected = selectedBtnCode == code,
                                        onClick = { selectedBtnCode = code },
                                        label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                    MappingType.AXIS -> {
                        HorizontalDivider()
                        Text("Eje:", style = MaterialTheme.typography.labelMedium)
                        axisOptions.forEach { (code, name) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedAxisCode == code, onClick = { selectedAxisCode = code })
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Valor al presionar: ${axisValue.toInt()}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = axisValue, onValueChange = { axisValue = it }, valueRange = -32767f..32767f)
                    }
                    MappingType.DPAD_ANALOG -> {
                        HorizontalDivider()
                        Text("Palanca:", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = analogStick == 0, onClick = { analogStick = 0 })
                            Text("Izquierda (Circle Pad / LS)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = analogStick == 1, onClick = { analogStick = 1 })
                            Text("Derecha (C-Stick / RS)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        // Bloque vacío o UI por defecto si MappingType.DPAD_HAT no requiere configuración extra
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    KeyMapping(
                        profileId  = 0, // se sobreescribe en el caller
                        keyCode    = physKey.keyCode,
                        keyLabel   = physKey.label,
                        mappingType = selectedType,
                        gamepadCode = when (selectedType) {
                            MappingType.BUTTON -> selectedBtnCode
                            MappingType.AXIS   -> selectedAxisCode
                            MappingType.DPAD_ANALOG -> analogStick
                            else -> 0
                        },
                        axisValue  = axisValue.toInt(),
                        isEnabled  = true
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (currentMapping != null) {
                    TextButton(onClick = onDelete) {
                        Text("Quitar", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

// ──────────────────────────────────────────────
// Exportar / Importar Perfil (JSON)
// ──────────────────────────────────────────────

private fun exportProfileData(context: Context, profile: Profile, mappings: List<KeyMapping>, uri: Uri) {
    try {
        val json = JSONObject()
        json.put("name", profile.name)
        json.put("description", profile.description)
        json.put("deviceType", profile.deviceType)
        json.put("analogMode", profile.analogMode)
        json.put("rampStep", profile.rampStep)

        val mappingsArray = JSONArray()
        mappings.forEach { m ->
            val mJson = JSONObject()
            mJson.put("keyCode", m.keyCode)
            mJson.put("keyLabel", m.keyLabel)
            mJson.put("mappingType", m.mappingType.name)
            mJson.put("gamepadCode", m.gamepadCode)
            mJson.put("axisValue", m.axisValue)
            mappingsArray.put(mJson)
        }
        json.put("mappings", mappingsArray)

        context.contentResolver.openOutputStream(uri)?.use {
            it.write(json.toString(4).toByteArray())
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private suspend fun importProfileData(context: Context, uri: Uri, db: AppDatabase) {
    withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext
            val json = JSONObject(jsonString)

            val profile = Profile(
                name = json.getString("name"),
                description = json.optString("description", ""),
                deviceType = json.optInt("deviceType", 1),
                analogMode = json.optInt("analogMode", 0),
                rampStep = json.optInt("rampStep", 4096)
            )

            val profileId = db.profileDao().insertProfile(profile)

            val mappingsArray = json.getJSONArray("mappings")
            for (i in 0 until mappingsArray.length()) {
                val mJson = mappingsArray.getJSONObject(i)
                val mapping = KeyMapping(
                    profileId = profileId,
                    keyCode = mJson.getInt("keyCode"),
                    keyLabel = mJson.getString("keyLabel"),
                    mappingType = MappingType.valueOf(mJson.getString("mappingType")),
                    gamepadCode = mJson.getInt("gamepadCode"),
                    axisValue = mJson.getInt("axisValue"),
                    isEnabled = true
                )
                db.keyMappingDao().insertMapping(mapping)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
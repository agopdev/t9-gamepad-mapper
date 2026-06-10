package com.t9mapper.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.t9mapper.T9GamepadApp
import com.t9mapper.data.model.AppProfileAssignment
import com.t9mapper.data.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val name: String,
    val icon: ImageBitmap
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(navController: NavController) {
    val context = LocalContext.current
    val db = (context.applicationContext as T9GamepadApp).database
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("t9gamepad_prefs", Context.MODE_PRIVATE) }
    var automationEnabled by remember {
        mutableStateOf(prefs.getBoolean("automation_enabled", false))
    }

    val assignments by db.appProfileAssignmentDao().getAllAssignments()
        .collectAsStateWithLifecycle(emptyList())
    val profiles by db.profileDao().getAllProfiles()
        .collectAsStateWithLifecycle(emptyList())

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var assignDialogApp by remember { mutableStateOf<InstalledApp?>(null) }
    var isLoadingApps by remember { mutableStateOf(false) }

    // Cargar apps instaladas
    LaunchedEffect(Unit) {
        isLoadingApps = true
        val apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
        installedApps = apps
        isLoadingApps = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ── Switch de automatización ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (automationEnabled)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Cambio automático de perfil",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (automationEnabled)
                            "Activo — el perfil cambia según la app"
                        else
                            "Inactivo — se usa el perfil predeterminado",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.7f)
                    )
                }
                Switch(
                    checked = automationEnabled,
                    onCheckedChange = { enabled ->
                        automationEnabled = enabled
                        prefs.edit().putBoolean("automation_enabled", enabled).apply()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!automationEnabled) {
            // Mensaje explicativo cuando está desactivado
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Con la automatización desactivada, el servicio usará el perfil marcado como predeterminado en la pantalla de Perfiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.8f)
                    )
                }
            }
        } else {
            // Búsqueda de apps
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar app…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoadingApps) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val filtered = installedApps.filter { app ->
                    searchQuery.isBlank() ||
                    app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
                }

                // Primero las apps con asignación, luego el resto
                val assignedPackages = assignments.map { it.packageName }.toSet()
                val sortedApps = filtered.sortedWith(
                    compareByDescending<InstalledApp> { it.packageName in assignedPackages }
                        .thenBy { it.name }
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (assignments.isNotEmpty()) {
                        item {
                            Text(
                                "Apps con perfil asignado",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    items(sortedApps, key = { it.packageName }) { app ->
                        val assignment = assignments.find { it.packageName == app.packageName }
                        val assignedProfile = profiles.find { it.id == assignment?.profileId }

                        AppAssignmentRow(
                            app = app,
                            assignedProfile = assignedProfile,
                            onClick = { assignDialogApp = app }
                        )
                    }
                }
            }
        }
    }

    // Diálogo de asignación
    assignDialogApp?.let { app ->
        val current = assignments.find { it.packageName == app.packageName }
        AssignProfileDialog(
            app = app,
            profiles = profiles,
            currentProfileId = current?.profileId,
            onDismiss = { assignDialogApp = null },
            onAssign = { profileId ->
                scope.launch {
                    val profile = profiles.find { it.id == profileId }
                    db.appProfileAssignmentDao().insertAssignment(
                        AppProfileAssignment(
                            packageName = app.packageName,
                            appName = app.name,
                            profileId = profileId
                        )
                    )
                }
                assignDialogApp = null
            },
            onRemove = {
                scope.launch {
                    db.appProfileAssignmentDao().deleteAssignmentByPackage(app.packageName)
                }
                assignDialogApp = null
            }
        )
    }
}

@Composable
private fun AppAssignmentRow(
    app: InstalledApp,
    assignedProfile: Profile?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (assignedProfile != null)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = "Ícono de ${app.name}",
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (assignedProfile != null) {
                    Text(
                        "→ ${assignedProfile.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(0.4f))
        }
    }
}

@Composable
private fun AssignProfileDialog(
    app: InstalledApp,
    profiles: List<Profile>,
    currentProfileId: Long?,
    onDismiss: () -> Unit,
    onAssign: (Long) -> Unit,
    onRemove: () -> Unit
) {
    var selectedProfileId by remember { mutableStateOf(currentProfileId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Asignar perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
                Spacer(Modifier.height(8.dp))
                if (profiles.isEmpty()) {
                    Text(
                        "No hay perfiles disponibles. Crea uno primero.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    profiles.forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedProfileId == profile.id,
                                onClick = { selectedProfileId = profile.id }
                            )
                            Column {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                if (profile.isDefault) {
                                    Text("Predeterminado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedProfileId?.let { onAssign(it) } },
                enabled = selectedProfileId != null
            ) { Text("Asignar") }
        },
        dismissButton = {
            Row {
                if (currentProfileId != null) {
                    TextButton(onClick = onRemove) {
                        Text("Quitar asignación", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

// ──────────────────────────────────────────────
// Cargar apps instaladas (excluir apps del sistema)
// ──────────────────────────────────────────────

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return try {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { info ->
                val isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                val isSystemWithLauncher = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    pm.getLaunchIntentForPackage(info.packageName) != null
                (isUserApp || isSystemWithLauncher) &&
                    info.packageName != context.packageName
            }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    name = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info).toImageBitmapSafe()
                )
            }
            .sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun Drawable.toImageBitmapSafe(): ImageBitmap {
    if (this is BitmapDrawable) {
        return this.bitmap.asImageBitmap()
    }
    
    // Dimensiones por defecto si el Drawable no tiene tamaño intrínseco
    val width = if (intrinsicWidth > 0) intrinsicWidth else 100
    val height = if (intrinsicHeight > 0) intrinsicHeight else 100
    
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    
    return bitmap.asImageBitmap()
}
package com.t9mapper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t9mapper.ui.screens.AutomationScreen
import com.t9mapper.ui.screens.DashboardScreen
import com.t9mapper.ui.screens.ProfilesScreen
import com.t9mapper.ui.theme.T9GamepadTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T9GamepadTheme {
                T9GamepadApp()
            }
        }
    }
}

// ──────────────────────────────────────────────
// Rutas de navegación
// ──────────────────────────────────────────────

sealed class Screen(val route: String, val label: String) {
    object Dashboard   : Screen("dashboard",   "Estado")
    object Profiles    : Screen("profiles",    "Perfiles")
    object Automation  : Screen("automation",  "Automatización")
}

// ──────────────────────────────────────────────
// Composable raíz
// ──────────────────────────────────────────────

@Composable
fun T9GamepadApp() {
    val navController = rememberNavController()

    val bottomNavItems = listOf(
        Triple(Screen.Dashboard,  Icons.Filled.Dashboard,  "Estado"),
        Triple(Screen.Profiles,   Icons.Filled.List,       "Perfiles"),
        Triple(Screen.Automation, Icons.Filled.Bolt,       "Auto"),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { (screen, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(navController = navController)
            }
            composable(Screen.Profiles.route) {
                ProfilesScreen(navController = navController)
            }
            composable(Screen.Automation.route) {
                AutomationScreen(navController = navController)
            }
            // Ruta de edición de perfil
            composable("profile_edit/{profileId}") { backStackEntry ->
                val profileId = backStackEntry.arguments?.getString("profileId")?.toLongOrNull() ?: -1L
                com.t9mapper.ui.screens.ProfileEditScreen(
                    profileId = profileId,
                    navController = navController
                )
            }
        }
    }
}

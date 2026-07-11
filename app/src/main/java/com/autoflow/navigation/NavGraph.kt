package com.autoflow.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autoflow.features.dashboard.DashboardViewModel
import com.autoflow.features.dashboard.ui.DashboardScreen
import com.autoflow.features.permissions.ui.PermissionScreen

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val viewModel: DashboardViewModel = viewModel()
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()

    val startDestination = if (dashboardState.allPermissionsGranted) {
        Routes.Dashboard
    } else {
        Routes.Permissions
    }

    LaunchedEffect(dashboardState.allPermissionsGranted, currentEntry?.destination?.route) {
        if (dashboardState.allPermissionsGranted && currentEntry?.destination?.route == Routes.Permissions) {
            navController.navigate(Routes.Dashboard) {
                popUpTo(Routes.Permissions) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.Permissions) {
            PermissionScreen(
                state = dashboardState,
                onRefreshPermissions = viewModel::refreshPermissions,
                onOpenAccessibility = {
                    @Suppress("DEPRECATION")
                    viewModel.getApplication<Application>().startActivity(
                        viewModel.getAccessibilitySettingsIntent()
                    )
                },
                onOpenOverlay = {
                    @Suppress("DEPRECATION")
                    viewModel.getApplication<Application>().startActivity(
                        viewModel.getOverlaySettingsIntent()
                    )
                },
                onOpenBattery = {
                    @Suppress("DEPRECATION")
                    viewModel.getApplication<Application>().startActivity(
                        viewModel.getBatteryOptimizationIntent()
                    )
                },
                onContinue = {
                    navController.navigate(Routes.Dashboard) {
                        popUpTo(Routes.Permissions) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.Dashboard) {
            DashboardScreen(
                state = dashboardState,
                onToggleAutomation = viewModel::toggleAutomation,
                onConfigurePermissions = {
                    navController.navigate(Routes.Permissions)
                }
            )
        }
    }
}

private object Routes {
    const val Permissions = "permissions"
    const val Dashboard = "dashboard"
}

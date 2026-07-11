package com.autoflow.features.permissions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.autoflow.features.dashboard.model.DashboardState
import com.autoflow.features.permissions.model.PermissionType
import com.autoflow.ui.common.AutoFlowColors
import com.autoflow.ui.components.PermissionCard
import com.autoflow.ui.components.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    state: DashboardState,
    onRefreshPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenBattery: () -> Unit,
    onContinue: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRefreshPermissions()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefreshPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Permissions",
                            fontSize = 24.sp,
                            color = AutoFlowColors.TextPrimary
                        )
                        Text(
                            text = "Complete setup to continue",
                            fontSize = 12.sp,
                            color = AutoFlowColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AutoFlowColors.Surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AutoFlowColors.Background)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.permissionStates.forEach { permission ->
                PermissionCard(
                    title = permission.title,
                    description = permission.description,
                    granted = permission.granted
                ) {
                    when (permission.type) {
                        PermissionType.ACCESSIBILITY -> onOpenAccessibility()
                        PermissionType.OVERLAY -> onOpenOverlay()
                        PermissionType.BATTERY_OPTIMIZATION -> onOpenBattery()
                    }
                }
            }

            PrimaryButton(
                text = if (state.allPermissionsGranted) "Continue to Dashboard" else "Grant Required Permissions",
                enabled = state.allPermissionsGranted,
                onClick = onContinue
            )
        }
    }
}

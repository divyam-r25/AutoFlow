package com.autoflow.features.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoflow.features.dashboard.model.DashboardState
import com.autoflow.ui.common.AutoFlowColors
import com.autoflow.ui.components.PrimaryButton
import com.autoflow.ui.components.StatusCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onToggleAutomation: () -> Unit,
    onConfigurePermissions: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AutoFlow",
                            fontSize = 24.sp,
                            color = AutoFlowColors.TextPrimary
                        )
                        Text(
                            text = "Dashboard",
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
            StatusCard(state = state)

            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = AutoFlowColors.Card)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Profile: ${state.selectedProfile?.name ?: \"Not selected\"}",
                        color = AutoFlowColors.TextPrimary,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Action points: ${state.clickActions.size}",
                        color = AutoFlowColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    state.clickActions.forEach { action ->
                        Text(
                            text = "• ${action.label} (${action.actionType.name}) - ${action.durationMs}ms / ${action.delayMs}ms",
                            color = AutoFlowColors.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (!state.allPermissionsGranted) {
                PrimaryButton(text = "Complete Permission Setup") {
                    onConfigurePermissions()
                }
            }

            PrimaryButton(
                text = if (state.isAutomationRunning) "Stop Automation" else "Start Automation",
                enabled = state.allPermissionsGranted
            ) {
                onToggleAutomation()
            }
        }
    }
}

package com.autoflow.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoflow.ui.common.AutoFlowColors
import com.autoflow.ui.components.PermissionCard
import com.autoflow.ui.components.PrimaryButton
import com.autoflow.ui.components.StatusCard

/**
 * The main dashboard screen for AutoFlow.
 *
 * Displays:
 * - A reactive status card (Running / Ready / Setup Required)
 * - Permission cards for Accessibility, Overlay, and Battery Optimization
 * - A Start/Stop button that's only enabled when all required permissions are granted
 *
 * Permission statuses are refreshed every time the screen resumes,
 * so the user sees updated states after returning from system settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()

    // Refresh permissions every time this screen resumes
    // (user may have toggled permissions in Settings and come back)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

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
                            text = "Professional Automation",
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

            // ── Status Card ──
            StatusCard(state = dashboardState)

            // ── Permission Cards ──

            PermissionCard(
                title = "Accessibility Service",
                description = "Required for automated clicks and gestures",
                granted = dashboardState.accessibilityGranted
            ) {
                viewModel.getAccessibilitySettingsIntent().let {
                    // startActivity needs a Context — we get it via the intent's FLAG_ACTIVITY_NEW_TASK
                    @Suppress("DEPRECATION")
                    viewModel.getApplication<android.app.Application>().startActivity(it)
                }
            }

            PermissionCard(
                title = "Floating Overlay",
                description = "Required to show click points on screen",
                granted = dashboardState.overlayGranted
            ) {
                @Suppress("DEPRECATION")
                viewModel.getApplication<android.app.Application>()
                    .startActivity(viewModel.getOverlaySettingsIntent())
            }

            PermissionCard(
                title = "Battery Optimization",
                description = "Prevents system from stopping automation",
                granted = dashboardState.batteryOptimizationDisabled
            ) {
                @Suppress("DEPRECATION")
                viewModel.getApplication<android.app.Application>()
                    .startActivity(viewModel.getBatteryOptimizationIntent())
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Start / Stop Button ──
            PrimaryButton(
                text = if (dashboardState.isAutomationRunning)
                    "Stop Automation"
                else
                    "Start Automation",
                enabled = dashboardState.allPermissionsGranted
            ) {
                viewModel.toggleAutomation()
            }

        }

    }

}

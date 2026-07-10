package com.autoflow.ui.screens

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoflow.ui.common.AutoFlowColors
import com.autoflow.ui.components.PermissionCard
import com.autoflow.ui.components.PrimaryButton
import com.autoflow.ui.components.StatusCard
import com.autoflow.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel()
) {

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()

    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Column {

                        Text(
                            text = "AutoFlow",
                            fontSize = 24.sp
                        )

                        Text(
                            text = "Professional Automation",
                            fontSize = 12.sp
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

            StatusCard()

            PermissionCard(
                title = "Accessibility Service",
                granted = dashboardState.accessibilityGranted
            ) {}

            PermissionCard(
                title = "Floating Overlay",
                granted = dashboardState.overlayGranted
            ) {}

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryButton(
                text = if (dashboardState.isAutomationRunning)
                    "Stop Automation"
                else
                    "Start Automation"
            ) {}

        }

    }

}
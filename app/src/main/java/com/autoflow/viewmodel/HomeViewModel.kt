package com.autoflow.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.autoflow.data.model.DashboardState

class HomeViewModel : ViewModel() {

    private val _dashboardState = MutableStateFlow(
        DashboardState()
    )

    val dashboardState: StateFlow<DashboardState> =
        _dashboardState.asStateFlow()

    fun updateAccessibilityPermission(
        granted: Boolean
    ) {

        _dashboardState.value =
            _dashboardState.value.copy(
                accessibilityGranted = granted
            )

    }

    fun updateOverlayPermission(
        granted: Boolean
    ) {

        _dashboardState.value =
            _dashboardState.value.copy(
                overlayGranted = granted
            )

    }

    fun updateAutomationStatus(
        running: Boolean
    ) {

        _dashboardState.value =
            _dashboardState.value.copy(
                isAutomationRunning = running
            )

    }

}
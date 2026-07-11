package com.autoflow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoflow.data.model.DashboardState
import com.autoflow.ui.common.AutoFlowColors

/**
 * A status card that reactively displays the current automation state.
 *
 * Shows one of three states:
 * - **Running**: Blue pulsing indicator, "Automation is active"
 * - **Ready**: Green checkmark, "Tap Start to begin"
 * - **Setup Required**: Amber warning, "Grant all permissions to start"
 */
@Composable
fun StatusCard(state: DashboardState) {

    val statusInfo = when {
        state.isAutomationRunning -> StatusInfo(
            title = "Running",
            subtitle = "Automation is active",
            color = AutoFlowColors.Primary,
            icon = Icons.Default.PlayArrow
        )
        state.allPermissionsGranted -> StatusInfo(
            title = "Ready",
            subtitle = "Tap Start to begin",
            color = AutoFlowColors.Success,
            icon = Icons.Default.CheckCircle
        )
        else -> StatusInfo(
            title = "Setup Required",
            subtitle = "Grant all permissions to start",
            color = AutoFlowColors.Warning,
            icon = Icons.Default.Warning
        )
    }

    // Pulsing animation when running
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val animatedColor by animateColorAsState(
        targetValue = statusInfo.color,
        animationSpec = tween(400),
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AutoFlowColors.Card
        ),
        shape = RoundedCornerShape(20.dp)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // Status indicator dot with optional pulse
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .alpha(if (state.isAutomationRunning) pulseAlpha else 1f)
                    .clip(CircleShape)
                    .background(animatedColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusInfo.icon,
                    contentDescription = statusInfo.title,
                    tint = animatedColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {

                Text(
                    text = statusInfo.title,
                    fontSize = 22.sp,
                    color = AutoFlowColors.TextPrimary
                )

                Text(
                    text = statusInfo.subtitle,
                    color = AutoFlowColors.TextSecondary,
                    fontSize = 14.sp
                )

            }

        }

    }

}

/**
 * Internal data class to bundle status display properties.
 */
private data class StatusInfo(
    val title: String,
    val subtitle: String,
    val color: Color,
    val icon: ImageVector
)
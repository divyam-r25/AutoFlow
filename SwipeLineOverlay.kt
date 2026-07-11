package com.autoflow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoflow.ui.common.AutoFlowColors

/**
 * A card that displays the status of a specific permission.
 * Shows a check/warning icon, title, description, and a chevron
 * indicating the card is tappable to launch settings.
 *
 * @param title The permission name (e.g., "Accessibility Service")
 * @param description Brief explanation of why this permission is needed
 * @param granted Whether the permission is currently granted
 * @param onClick Action to perform when tapped (typically launches system settings)
 */
@Composable
fun PermissionCard(
    title: String,
    description: String = "",
    granted: Boolean,
    onClick: () -> Unit
) {

    val iconColor by animateColorAsState(
        targetValue = if (granted) AutoFlowColors.Success else AutoFlowColors.Error,
        animationSpec = tween(400),
        label = "permColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = AutoFlowColors.Card
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = if (granted)
                    Icons.Default.CheckCircle
                else
                    Icons.Default.Warning,
                contentDescription = if (granted) "Granted" else "Required",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = title,
                    color = AutoFlowColors.TextPrimary,
                    fontSize = 16.sp
                )

                Text(
                    text = if (granted) "Granted" else description.ifEmpty { "Permission Required" },
                    color = if (granted) AutoFlowColors.Success.copy(alpha = 0.8f)
                    else AutoFlowColors.TextSecondary,
                    fontSize = 13.sp
                )

            }

            // Show chevron only when not yet granted (tappable)
            if (!granted) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open settings",
                    tint = AutoFlowColors.TextTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }

        }

    }

}
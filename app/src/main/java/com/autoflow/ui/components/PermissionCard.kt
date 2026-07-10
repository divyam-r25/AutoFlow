package com.autoflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoflow.ui.common.AutoFlowColors

@Composable
fun PermissionCard(
    title: String,
    granted: Boolean,
    onClick: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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

            val icon: ImageVector =
                if (granted)
                    Icons.Default.CheckCircle
                else
                    Icons.Default.Warning

            val color =
                if (granted)
                    AutoFlowColors.Success
                else
                    AutoFlowColors.Error

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = title,
                    color = AutoFlowColors.TextPrimary,
                    fontSize = 18.sp
                )

                Text(
                    text = if (granted) "Granted" else "Permission Required",
                    color = AutoFlowColors.TextSecondary
                )

            }

        }

    }

}
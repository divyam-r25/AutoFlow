package com.autoflow.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoflow.ui.common.AutoFlowColors

/**
 * A full-width primary action button used for the main "Start/Stop Automation" action.
 *
 * @param text The button label
 * @param enabled Whether the button is interactive. When disabled, it appears faded.
 * @param onClick The action to perform when clicked
 */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AutoFlowColors.Primary,
            disabledContainerColor = AutoFlowColors.Primary.copy(alpha = 0.3f),
            disabledContentColor = AutoFlowColors.TextPrimary.copy(alpha = 0.5f)
        )
    ) {

        Text(
            text = text,
            fontSize = 18.sp
        )

    }

}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AccentBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldOnPrimary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AccessibilityDialog(
    onEnableClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, AccentBorder, RoundedCornerShape(24.dp))
            .testTag("accessibility_dialog"),
        containerColor = DarkSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_accessibility_new),
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.service_required_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
        },
        text = {
            Text(
                text = stringResource(R.string.service_required_message),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp,
                    color = TextSecondary
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onEnableClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = EmeraldOnPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("enable_accessibility_button")
            ) {
                Text(
                    text = stringResource(R.string.enable_service_btn),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("cancel_dialog_button")
            ) {
                Text(
                    text = stringResource(R.string.cancel_btn),
                    color = TextSecondary
                )
            }
        }
    )
}

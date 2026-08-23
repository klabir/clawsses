package com.clawsses.phone.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clawsses.phone.notifications.NotificationRelayService

@Composable
fun NotificationsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(NotificationRelayService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean(NotificationRelayService.KEY_ENABLED, false)) }
    var allowedPackages by remember {
        mutableStateOf(prefs.getString(NotificationRelayService.KEY_ALLOWED_PACKAGES, "").orEmpty())
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notification cards", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off by default. Only exact package names in the allowlist are relayed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(NotificationRelayService.KEY_ENABLED, it).apply()
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = allowedPackages,
                onValueChange = {
                    allowedPackages = it
                    prefs.edit().putString(NotificationRelayService.KEY_ALLOWED_PACKAGES, it).apply()
                },
                label = { Text("Allowed package names") },
                supportingText = { Text("Comma-separated, for example com.google.android.gm") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant notification access")
            }
        }
    }
}

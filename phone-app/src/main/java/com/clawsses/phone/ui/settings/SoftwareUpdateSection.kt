package com.clawsses.phone.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawsses.phone.BuildConfig
import com.clawsses.phone.glasses.ApkInstaller

@Composable
fun SoftwareUpdateSection(
    installState: ApkInstaller.InstallState,
    onInstall: () -> Unit,
    onVerifyInstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        VersionContent()
        Spacer(Modifier.height(16.dp))

        when (installState) {
            is ApkInstaller.InstallState.Idle ->
                IdleContent(onInstall)

            is ApkInstaller.InstallState.InitializingWifiP2P ->
                ProgressContent("Establishing WiFi P2P...", -1, null, onCancel = null)

            is ApkInstaller.InstallState.InitializingWifiHotspot ->
                ProgressContent("Connecting to glasses hotspot...", -1, null, onCancel = null)

            is ApkInstaller.InstallState.AwaitingHiRokidAuthorization ->
                ProgressContent("Approve the installer in Hi Rokid...", -1, null, onCancel)

            is ApkInstaller.InstallState.ConnectingHiRokid ->
                ProgressContent(installState.message, -1, "Keep Hi Rokid and the glasses awake", onCancel)

            is ApkInstaller.InstallState.AwaitingPeerOwnership ->
                ProgressContent(
                    "Installed. Waiting for Clawsses to reclaim the glasses connection...",
                    -1,
                    "Expected Build ${installState.expectedBuild}",
                    onCancel,
                )

            is ApkInstaller.InstallState.VerifyingPeer ->
                ProgressContent(
                    "Verifying glasses Build ${installState.expectedBuild}...",
                    -1,
                    "Wake the glasses if they do not reconnect automatically",
                    onCancel,
                )

            is ApkInstaller.InstallState.InstalledPendingVerification ->
                PendingVerificationContent(installState, onVerifyInstall, onInstall)

            is ApkInstaller.InstallState.PreparingApk ->
                ProgressContent("Preparing APK...", -1, null, onCancel = null)

            is ApkInstaller.InstallState.Uploading ->
                ProgressContent(installState.message, installState.progress, "Do not disconnect the glasses", onCancel)

            is ApkInstaller.InstallState.Installing ->
                ProgressContent(installState.message, -1, "Do not disconnect the glasses", onCancel = null)

            is ApkInstaller.InstallState.Success ->
                SuccessContent(installState.message, onInstall)

            is ApkInstaller.InstallState.Error ->
                ErrorContent(installState.message, installState.canRetry, onInstall)
        }
    }
}

@Composable
private fun PendingVerificationContent(
    state: ApkInstaller.InstallState.InstalledPendingVerification,
    onVerify: () -> Unit,
    onInstallAgain: () -> Unit,
) {
    Text(
        "Installed — verification pending",
        style = MaterialTheme.typography.bodyLarge,
        color = Color(0xFFFFC107),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        state.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Verify installed build")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onInstallAgain, modifier = Modifier.fillMaxWidth()) {
        Text("Install again")
    }
}

@Composable
private fun VersionContent() {
    Text(
        "Phone App ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Bundled Glasses App ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IdleContent(
    onInstall: () -> Unit,
) {
    Text(
        "Glasses App",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Clawsses uses the official Hi Rokid installer when available and automatically falls " +
            "back to the connected CXR-M transport.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onInstall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Install or update Glasses App")
    }
}

@Composable
private fun ProgressContent(
    message: String,
    progress: Int,
    warning: String?,
    onCancel: (() -> Unit)?,
) {
    Text(
        "Installing Glasses App",
        style = MaterialTheme.typography.bodyLarge,
    )

    Spacer(Modifier.height(12.dp))

    if (progress >= 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "$progress%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (warning != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            warning,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFC107),
        )
    }

    if (onCancel != null) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun SuccessContent(message: String, onInstallAgain: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Installation Complete",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF4CAF50),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onInstallAgain,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Install Again")
    }
}

@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = Color(0xFFF44336),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Installation Failed",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFF44336),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (canRetry) {
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Try Again")
        }
    }
}

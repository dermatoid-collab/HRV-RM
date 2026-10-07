package com.hrvrm.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.hrvrm.app.BuildConfig
import com.hrvrm.app.ui.nav.AppResumeSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onBackupFilePicked(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.pickBackupFolder(uri)
    }
    // "*/*" rather than a specific mime type: some file providers report .gz backups as
    // application/octet-stream or omit a type entirely, which would gray the file out in
    // the picker — importBackup() sniffs the gzip magic bytes itself instead of trusting
    // whatever type the picker reports.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Intervals.icu",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Find your API key and Athlete ID under intervals.icu → Settings → Developer Settings.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = state.athleteId,
            onValueChange = viewModel::onAthleteIdChanged,
            label = { Text("Athlete ID (e.g. i123456)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChanged,
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-upload", fontWeight = FontWeight.SemiBold)
                Text(
                    "Upload each measurement to Intervals.icu right after it completes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = state.autoUpload, onCheckedChange = viewModel::onAutoUploadChanged)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::save) {
                Text("Save")
            }
            OutlinedButton(onClick = viewModel::testConnection, enabled = !state.testInProgress) {
                if (state.testInProgress) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                }
                Text("Test connection")
            }
        }

        if (state.saved && state.testResult == null) {
            Text(
                "Settings saved.",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.testResult?.let { result ->
            val isSuccess = result.startsWith("Connected")
            Text(
                result,
                fontSize = 13.sp,
                color = if (isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(onClick = viewModel::sendTestHrvValue, enabled = !state.testHrvInProgress) {
            if (state.testHrvInProgress) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            Text("Send test HRV-RM value to today")
        }

        state.testHrvResult?.let { result ->
            val isSuccess = result.startsWith("Sent")
            Text(
                result,
                fontSize = 13.sp,
                color = if (isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        Text(
            "Backup",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Your history lives only on this device. Export before uninstalling, import to " +
                "restore — includes your Intervals.icu credentials, so treat the file like a " +
                "password. A weekly reminder nudges you; nothing exports automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    viewModel.exportBackupFile { uri ->
                        if (uri == null) return@exportBackupFile
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/gzip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        AppResumeSignal.suppressNextResume = true
                        context.startActivity(Intent.createChooser(intent, "Export HRV-RM backup"))
                    }
                },
                enabled = !state.backupInProgress,
            ) {
                if (state.backupInProgress) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                }
                Text("Export backup")
            }
            OutlinedButton(
                onClick = {
                    AppResumeSignal.suppressNextResume = true
                    importLauncher.launch(arrayOf("*/*"))
                },
                enabled = !state.backupInProgress,
            ) {
                Text("Import backup")
            }
        }

        state.backupResult?.let { result ->
            val isSuccess = result.startsWith("Imported")
            Text(
                result,
                fontSize = 13.sp,
                color = if (isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep raw sensor data", fontWeight = FontWeight.SemiBold)
                Text(
                    "Keeps raw camera samples (~11 KB each) so \"Export raw data\" also works " +
                        "later from History. Off by default; not included in the backup above.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = state.keepRawData, onCheckedChange = viewModel::onKeepRawDataChanged)
        }

        HorizontalDivider()

        Text(
            "Backup folder",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Pick a folder (Google Drive, Dropbox, etc.) and each measurement saves there " +
                "automatically, along with your credentials. Use History's sync icon to catch " +
                "up or restore after reinstalling.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.backupFolderName != null) {
            Text(
                "Folder: ${state.backupFolderName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    AppResumeSignal.suppressNextResume = true
                    folderLauncher.launch(null)
                },
            ) {
                Text(if (state.backupFolderName == null) "Choose folder" else "Change folder")
            }
            if (state.backupFolderName != null) {
                OutlinedButton(onClick = viewModel::forgetBackupFolder) {
                    Text("Forget folder")
                }
            }
        }

        HorizontalDivider()

        Text(
            "About",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )

        // Every methodology this app's own calculations are built on — kept here, not just
        // in code comments, since these are what justify the numbers this app shows as
        // more than an arbitrary in-house formula. Each one mirrors a published method
        // in-house (see the cited function/class for the exact formula) rather than
        // reproducing any vendor's proprietary algorithm.
        Text(
            "Data sources & references",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "• Heart rate signal: rear camera + flash photoplethysmography (PPG), red " +
                    "channel — Kumar et al.; iPhysioMeter validation work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Beat detection: Elgendi et al. (2013), \"Systolic Peak Detection in " +
                    "Acceleration Photoplethysmograms Measured from Emergency Responders in " +
                    "Tropical Conditions\", PLoS ONE 8(10): e76585.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Artifact correction informed by Lipponen & Tarvainen (2019), \"A robust " +
                    "algorithm for heart rate variability time series artefact correction " +
                    "using novel beat classification\", J Med Eng Technol; and Altini, M., " +
                    "published writing on PPG artifact removal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• HRV score, baseline and normal range: mirrors Altini, M., \"Daily score, " +
                    "baseline and normal range: an overview\" (HRV4Training methodology).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Stress Index: Baevsky & Chernikova — standard cardiovascular/space-" +
                    "medicine formula (SI = AMo / (2 × Mo × MxDMn)).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Poincaré SD1/SD2, SDNN, RMSSD, pNN50: standard time-domain/nonlinear HRV " +
                    "analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Syncs with Intervals.icu (intervals.icu) when configured above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // BUILD_NUMBER/BUILD_DATE come from CI (-PbuildNumber/-PbuildDate in
        // build-apk.yml), matching the downloaded artifact's own "..._buildXXX_ddmmyy"
        // filename -- "local" / today's date for an on-device Gradle build outside CI.
        val rawBuildDate = BuildConfig.BUILD_DATE
        val formattedBuildDate = if (rawBuildDate.length == 6) {
            "${rawBuildDate.substring(0, 2)}/${rawBuildDate.substring(2, 4)}/${rawBuildDate.substring(4, 6)}"
        } else {
            rawBuildDate
        }
        Text(
            "Build #${BuildConfig.BUILD_NUMBER} · $formattedBuildDate · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ©RM",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Import backup?") },
            text = {
                Text(
                    "This adds any measurements not already saved, and replaces your saved " +
                        "Intervals.icu API key and Athlete ID with the ones in this backup, " +
                        "if it has them. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
            },
        )
    }
}

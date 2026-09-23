package com.hrvrm.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
        if (uri != null) viewModel.importBackup(uri)
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Intervals.icu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Find your API key and Athlete ID in your intervals.icu account settings, " +
                "under \"Developer Settings\".",
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

        Text("Backup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your measurement history lives only on this device. Export it before " +
                "uninstalling or switching builds, and import it back afterwards. The backup " +
                "(gzip-compressed) has the computed results of each measurement (scores, " +
                "metrics, RR series) — not raw sensor data — plus your Intervals.icu API key " +
                "and Athlete ID, so a restore doesn't need them re-typed. Treat the exported " +
                "file like a password: whatever app you share it through can read that key. " +
                "A weekly notification reminds you; it never exports on its own.",
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
                onClick = { importLauncher.launch(arrayOf("*/*")) },
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

        HorizontalDivider()

        Text("Backup folder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Pick a folder — including one backed by Google Drive, Dropbox, or another " +
                "cloud app, if it's installed — and every measurement is written there as " +
                "its own small file the moment it's saved, plus one settings file with your " +
                "Intervals.icu credentials. Use History's sync icon to catch up on anything " +
                "missed, or to pull your whole history back after reinstalling the app.",
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
            OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                Text(if (state.backupFolderName == null) "Choose folder" else "Change folder")
            }
            if (state.backupFolderName != null) {
                OutlinedButton(onClick = viewModel::forgetBackupFolder) {
                    Text("Forget folder")
                }
            }
        }
    }
}

package com.hrvrm.app.ui.settings

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

        Text("Testing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Sample history is written only to this device's local database, never to " +
                "Intervals.icu — but it does feed your real rolling baseline until cleared.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::seedSampleHistory, enabled = !state.seedInProgress) {
                if (state.seedInProgress) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                }
                Text("Seed sample history")
            }
            OutlinedButton(
                onClick = viewModel::requestClearHistory,
                enabled = !state.clearInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Clear all history")
            }
        }

        state.seedResult?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        }
        state.clearResult?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        }

        if (state.showClearConfirm) {
            AlertDialog(
                onDismissRequest = viewModel::dismissClearHistory,
                title = { Text("Clear all history?") },
                text = {
                    Text(
                        "This permanently deletes every measurement on this device — " +
                            "sample data and real readings alike.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmClearHistory) {
                        Text("Delete everything", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissClearHistory) { Text("Cancel") }
                },
            )
        }
    }
}

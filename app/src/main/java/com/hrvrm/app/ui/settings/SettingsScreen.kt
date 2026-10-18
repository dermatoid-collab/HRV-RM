package com.hrvrm.app.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hrvrm.app.BuildConfig
import com.hrvrm.app.ui.nav.AppResumeSignal
import com.hrvrm.app.ui.theme.AutoThemeMode
import com.hrvrm.app.ui.theme.ThemeMode
import com.hrvrm.app.ui.theme.themeSwatches
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
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
        // Same page-title style as the Charts/History tabs' own "Charts"/"History" headers.
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)

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

        OutlinedButton(onClick = viewModel::resyncHistory, enabled = !state.resyncInProgress) {
            if (state.resyncInProgress) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            Text(if (state.resyncInProgress) "Syncing… ${state.resyncProgress ?: ""}" else "Resync all history")
        }

        state.resyncResult?.let { result ->
            val isError = result.startsWith("Set your") || result.contains("failed")
            Text(
                result,
                fontSize = 13.sp,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }

        HorizontalDivider()

        Text(
            "Reminder",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Daily reminder", fontWeight = FontWeight.SemiBold)
                Text(
                    "A nudge to take today's measurement.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = state.measurementReminderEnabled, onCheckedChange = viewModel::onMeasurementReminderEnabledChanged)
        }

        if (state.measurementReminderEnabled) {
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> viewModel.onMeasurementReminderTimeChanged(hour, minute) },
                        state.measurementReminderHour,
                        state.measurementReminderMinute,
                        DateFormat.is24HourFormat(context),
                    ).show()
                },
            ) {
                Text("Reminder time: %02d:%02d".format(state.measurementReminderHour, state.measurementReminderMinute))
            }
        }

        NotificationPermissionWarning()

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
            "Appearance",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )

        Text(
            "Auto theme",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutoThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.onAutoThemeModeChanged(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.autoThemeMode == mode, onClick = { viewModel.onAutoThemeModeChanged(mode) })
                Text(mode.label, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }

        val paletteListEnabled = state.autoThemeMode == AutoThemeMode.OFF
        if (!paletteListEnabled) {
            Text(
                "Picking a palette automatically — turn Auto theme off to choose one yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val themeGroups = listOf(
            null to listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.HRV_RM_LEGACY),
            "Dark palettes" to ThemeMode.entries.filter { it.dark == true && it != ThemeMode.DARK && it != ThemeMode.HRV_RM_LEGACY },
            "Light palettes" to ThemeMode.entries.filter { it.dark == false && it != ThemeMode.LIGHT },
        )
        Column(modifier = Modifier.alpha(if (paletteListEnabled) 1f else 0.4f)) {
            themeGroups.forEach { (title, modes) ->
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                modes.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (paletteListEnabled) {
                                    Modifier.clickable { viewModel.onThemeModeChanged(mode) }
                                } else {
                                    Modifier
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.onThemeModeChanged(mode) },
                            enabled = paletteListEnabled,
                        )
                        Text(mode.label, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        // What the palette looks like: page, bar and the two accents.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 8.dp)) {
                            themeSwatches(mode).forEach { color ->
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .background(color, CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            "Glossary",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 0.75f,
            fontWeight = FontWeight.Bold,
        )
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                glossaryEntries.forEach { (term, definition) -> GlossaryEntry(term, definition) }
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
                "• CV7: coefficient of variation of ln(RMSSD) over the last 7 readings " +
                    "(CV7 = 100 × SD7 / Mean7) — standard statistical dispersion measure, " +
                    "applied here to track week-to-week autonomic response stability.",
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

/** Every abbreviation and named value shown anywhere in the app, alphabetical. Term and
 * definition split apart (rather than one literal string per row) so the term alone -- not
 * the dash or the definition that follows it -- renders bold; see [GlossaryEntry]. */
private val glossaryEntries = listOf(
    "ASI" to
        "Autonomic Status Index, a personalized, experimental 0–100 index designed to quantify " +
            "unfavorable deviations in an athlete's autonomic profile relative to their historical " +
            "baseline. ASI is not a medical diagnostic tool or a standalone measure of training " +
            "readiness.\n\n" +
            "The score combines five physiological indicators: Daily HRV / 7-day HRV / RHR / " +
            "7-day RHR / CV7. HRV and RHR deviations are standardized using individual historical " +
            "means and standard deviations (z-scores), while CV7 is evaluated against historical " +
            "percentiles.\n\n" +
            "Score calculation:\n" +
            "ASI = round(clamp(100 − P_HRV − P_HRV7 − P_RHR − P_RHR7 − P_CV7, 0, 100))\n\n" +
            "The score starts at 100 and decreases through progressively increasing penalties for " +
            "unfavorable deviations:\n" +
            "- Daily HRV: up to 25 points.\n" +
            "- 7-day HRV: up to 20 points.\n" +
            "- Daily RHR: up to 25 points.\n" +
            "- 7-day RHR: up to 20 points.\n" +
            "- CV7: up to 10 points.\n\n" +
            "Higher-than-usual HRV and lower-than-usual RHR do not generate bonus points. " +
            "Unusually extreme autonomic responses are flagged separately.\n\n" +
            "Interpretation: higher ASI scores indicate fewer unfavorable deviations from the " +
            "athlete's habitual autonomic profile. Lower scores reflect increasingly pronounced " +
            "deviations.",
    "Baseline (HRV baseline, 7-reading avg)" to
        "rolling average of the last 7 HRV score readings; more stable than the day's own value.",
    "Building baseline" to
        "shown when there aren't yet enough past readings (minimum 3) to compute a reliable normal range.",
    "CV7" to
        "Coefficient of Variation: 100 × SD / Mean of the last 7 ln(RMSSD) readings. Tracks " +
            "week-to-week autonomic response instability.",
    "HRV" to "Heart Rate Variability.",
    "HRV score" to
        "the main value shown (e.g. \"10.0\"): today's own 2 × ln(RMSSD), unsmoothed, on an " +
            "HRV4Training-style log scale.",
    "IBI" to "Inter-Beat Interval: time between two consecutive heartbeats (same as RR).",
    "ln scale" to "logarithmic scale: marks a chart as showing HRV score rather than Score %.",
    "Normal range" to "the band (±1 SD over the last 60 readings) a reading is compared against.",
    "Outside range" to "today's reading falls outside your personal normal range.",
    "pNN50" to "percentage of consecutive beat-to-beat differences greater than 50ms.",
    "PPG" to "Photoplethysmography: the camera + flash measurement technique this app uses.",
    "RHR" to "Resting Heart Rate.",
    "RMSSD" to "Root Mean Square of Successive Differences between consecutive beats.",
    "Score %" to
        "HRV score rescaled to 0-100 (your own z-score × 20 + 50), for an easier-to-read trend line.",
    "SD1" to "Poincaré plot short-term (beat-to-beat) variability.",
    "SD2" to "Poincaré plot long-term variability.",
    "SDNN" to
        "Standard Deviation of NN intervals: the spread of all beat-to-beat intervals in a measurement.",
    "Stress index" to "Baevsky's Stress Index, a measure of autonomic nervous system \"rigidity\".",
    "Within range" to "today's reading falls inside your personal normal range.",
)

@Composable
private fun GlossaryEntry(term: String, definition: String) {
    Text(
        buildAnnotatedString {
            append("• ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(term) }
            append(" — ")
            append(definition)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Both reminders (daily measurement, weekly backup) post through POST_NOTIFICATIONS
 * (Android 13+) -- if it's denied, they silently never show, with no error anywhere, which
 * is exactly what "the reminder never arrives" looks like from the outside. Android only
 * offers its own request dialog once automatically; after a denial it won't ask again, so
 * this is the self-service way back in, deep-linking straight to this app's notification
 * settings rather than a generic request that may do nothing. Rechecked on every resume
 * (e.g. coming back from that settings screen) so the warning clears itself once granted.
 */
@Composable
private fun NotificationPermissionWarning() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Notifications are off", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(
                "Reminders can't show without this -- Android won't ask again on its own once denied.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = {
            AppResumeSignal.suppressNextResume = true
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            )
        }) {
            Text("Enable")
        }
    }
}

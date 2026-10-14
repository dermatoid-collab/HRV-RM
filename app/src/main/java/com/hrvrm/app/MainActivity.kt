package com.hrvrm.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hrvrm.app.ui.nav.AppNavHost
import com.hrvrm.app.ui.nav.AppResumeSignal
import com.hrvrm.app.ui.theme.HrvRmTheme
import com.hrvrm.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as HrvRmApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent, auto-appearance bars: HrvRmTheme itself sets the actual light/dark icon
        // appearance once the chosen palette is known (see its SideEffect) -- this initial call
        // just makes the bars edge-to-edge, it doesn't decide their icon color.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val openBackup = intent?.getBooleanExtra(EXTRA_OPEN_BACKUP, false) ?: false
        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsState(initial = ThemeMode.HRV_RM_LEGACY)
            HrvRmTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RequestNotificationPermissionOnLaunch()
                        BatteryOptimizationPrompt()
                        AppNavHost(startAtSettings = openBackup)
                    }
                }
            }
        }
    }

    companion object {
        /** Set on the intent the weekly backup-reminder notification launches — see BackupReminderWorker. */
        const val EXTRA_OPEN_BACKUP = "open_backup"
    }
}

/** POST_NOTIFICATIONS is required from Android 13 for the weekly backup reminder to show at all. */
@Composable
private fun RequestNotificationPermissionOnLaunch() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppResumeSignal.suppressNextResume = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * One-time, first-launch nudge to exempt the app from battery optimization -- without it,
 * Android (and especially OEM battery managers) can silently defer or drop the daily
 * measurement reminder's WorkManager job. Shown at most once ever (tracked in SettingsStore);
 * skipped entirely if the exemption is already granted, e.g. on a reinstall.
 */
@Composable
private fun BatteryOptimizationPrompt() {
    val context = LocalContext.current
    val settingsStore = (context.applicationContext as HrvRmApp).container.settingsStore
    val scope = rememberCoroutineScope()
    val alreadyShown by settingsStore.batteryOptimizationPromptShown.collectAsState(initial = true)
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(alreadyShown) {
        if (alreadyShown) return@LaunchedEffect
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            settingsStore.setBatteryOptimizationPromptShown(true)
        } else {
            showDialog = true
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                scope.launch { settingsStore.setBatteryOptimizationPromptShown(true) }
            },
            title = { Text("Keep the daily reminder reliable") },
            text = {
                Text(
                    "Android's battery optimization can silently stop the 05:00 measurement " +
                        "reminder from firing. Set HRV-RM to \"Unrestricted\" / \"No restrictions\" " +
                        "for battery usage so it keeps working.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { settingsStore.setBatteryOptimizationPromptShown(true) }
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                        )
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { settingsStore.setBatteryOptimizationPromptShown(true) }
                }) { Text("Not now") }
            },
        )
    }
}

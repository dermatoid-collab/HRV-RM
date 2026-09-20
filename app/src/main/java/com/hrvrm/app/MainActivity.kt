package com.hrvrm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hrvrm.app.ui.nav.AppNavHost
import com.hrvrm.app.ui.theme.HrvRmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openBackup = intent?.getBooleanExtra(EXTRA_OPEN_BACKUP, false) ?: false
        setContent {
            HrvRmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RequestNotificationPermissionOnLaunch()
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
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

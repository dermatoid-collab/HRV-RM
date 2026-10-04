package com.hrvrm.app.ui.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hrvrm.app.ui.history.HistoryScreen
import com.hrvrm.app.ui.history.MeasurementDetailScreen
import com.hrvrm.app.ui.measure.MeasureScreen
import com.hrvrm.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private sealed class Destination(val label: String) {
    data object Measure : Destination("Measure")
    data object History : Destination("History")
    data object Settings : Destination("Settings")
}

private val destinations = listOf(Destination.Measure, Destination.History, Destination.Settings)

/**
 * Top-level navigation: "main" hosts the 3 tabs as swipeable pager pages (plus the same
 * icon row tapped directly), "measurementDetail/{id}" is a normal pushed screen reached by
 * tapping a History row or the dashboard's "Last measurement" card -- it's deliberately
 * NOT a pager page, since there's no sensible "swipe into a specific measurement" gesture.
 */
@Composable
fun AppNavHost(startAtSettings: Boolean = false) {
    val navController = rememberNavController()
    // Bumped once per app resume (ON_START after the activity has already been stopped, not
    // the initial cold launch) so MainTabsScreen can snap its pager back to the first tab --
    // see the LaunchedEffect below that reads it.
    var resumeSignal by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var hasStartedOnce = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStartedOnce) {
                    // Reopened after being backgrounded with home/recents (not a force-stop,
                    // which would tear down this whole composition and restart cold anyway) --
                    // always land back on the Today dashboard instead of wherever was left open.
                    navController.popBackStack(route = "main", inclusive = false)
                    resumeSignal++
                } else {
                    hasStartedOnce = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainTabsScreen(
                startAtSettings = startAtSettings,
                onMeasurementClick = { id -> navController.navigate("measurementDetail/$id") },
                resumeSignal = resumeSignal,
            )
        }
        composable("measurementDetail/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            if (id != null) {
                MeasurementDetailScreen(measurementId = id, onBack = { navController.popBackStack() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabsScreen(startAtSettings: Boolean, onMeasurementClick: (Long) -> Unit, resumeSignal: Int) {
    val pagerState = rememberPagerState(
        initialPage = if (startAtSettings) destinations.indexOf(Destination.Settings) else 0,
        pageCount = { destinations.size },
    )
    val scope = rememberCoroutineScope()

    // resumeSignal starts at 0 and only increments on a real app resume (see AppNavHost), so
    // this never fires during the initial composition -- startAtSettings keeps working for a
    // fresh launch from the backup-reminder notification.
    LaunchedEffect(resumeSignal) {
        if (resumeSignal > 0) pagerState.scrollToPage(0)
    }

    Scaffold(
        topBar = {
            TopTabBar(
                selectedIndex = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding),
        ) { page ->
            when (destinations[page]) {
                Destination.Measure -> MeasureScreen(
                    onMeasurementClick = onMeasurementClick,
                    onTrendClick = {
                        scope.launch { pagerState.animateScrollToPage(destinations.indexOf(Destination.History)) }
                    },
                )
                Destination.History -> HistoryScreen(onMeasurementClick = onMeasurementClick)
                Destination.Settings -> SettingsScreen()
            }
        }
    }
}

private val TAB_UNDERLINE_WIDTH = 32.dp
private val TAB_ICON_SIZE = 28.dp

@Composable
private fun TopTabBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // enableEdgeToEdge() draws this bar's Row under the system status bar unless it
            // claims that inset itself -- without this the title/icons crowded the clock and
            // battery icons instead of sitting cleanly below them.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "HRV-RM",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        destinations.forEachIndexed { index, destination ->
            val selected = index == selectedIndex
            val icon = when (destination) {
                Destination.Measure -> Icons.Filled.Favorite
                Destination.History -> Icons.Filled.History
                Destination.Settings -> Icons.Filled.Settings
            }
            Box(contentAlignment = Alignment.TopCenter) {
                IconButton(onClick = { onSelect(index) }) {
                    Icon(
                        icon,
                        contentDescription = destination.label,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(TAB_ICON_SIZE),
                    )
                }
                // IconButton's 48dp touch target centers the 28dp icon with 10dp above and
                // below it, so the icon's own bottom edge sits at 38dp -- +8dp gap per spec.
                Box(
                    modifier = Modifier
                        .padding(top = 46.dp)
                        .width(if (selected) TAB_UNDERLINE_WIDTH else 0.dp)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp)),
                )
            }
        }
    }
}

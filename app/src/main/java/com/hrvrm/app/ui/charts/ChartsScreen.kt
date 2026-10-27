package com.hrvrm.app.ui.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrvrm.app.ui.asi.toComposeColor
import com.hrvrm.app.ui.history.AsiTrendChart
import com.hrvrm.app.ui.history.HistoryViewModel
import com.hrvrm.app.ui.history.HrvTrendChart
import com.hrvrm.app.ui.history.RhrTrendChart
import com.hrvrm.app.ui.theme.RhrBandOverlay
import java.time.LocalDate

/**
 * All three trend charts on their own page -- split out of History (which otherwise didn't fit
 * on one screen with all three cards plus the measurement list). Order top to bottom: ASI, HRV,
 * RHR. Shares [HistoryViewModel] with the History tab (same NavBackStackEntry-scoped instance,
 * same pattern [com.hrvrm.app.ui.history.HistoryScreen] already relies on) rather than
 * duplicating its daily-point flows in a second ViewModel.
 */
@Composable
fun ChartsScreen(viewModel: HistoryViewModel = viewModel()) {
    val dailyTrend by viewModel.dailyTrend.collectAsStateWithLifecycle()
    val dailyRhrTrend by viewModel.dailyRhrTrend.collectAsStateWithLifecycle()
    val dailyAsiTrend by viewModel.dailyAsiTrend.collectAsStateWithLifecycle()

    val hasAnyChart = dailyAsiTrend.size >= 2 || dailyTrend.size >= 2 || dailyRhrTrend.size >= 2

    // Each tile's border tracks whichever bar is currently selected/shown in its own chart
    // (falls back to today's/the fixed color below until that chart's first SideEffect fires).
    var asiBorderColor by remember { mutableStateOf<Color?>(null) }
    var hrvBorderColor by remember { mutableStateOf<Color?>(null) }
    var rhrBorderColor by remember { mutableStateOf<Color?>(null) }

    // Shared across all three charts so dragging on any one of them moves the vertical
    // selection line in the other two as well -- a calendar date rather than a raw index,
    // since each chart's own points list has its own length/start (e.g. ASI needs more
    // history before it has any data at all, so index N in one list isn't index N in another).
    // null means "no explicit selection", which each chart independently reads as "show the
    // latest point", same default as before this was shared.
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Charts",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            // No extra status-bar inset here -- Scaffold's shared TopTabBar already claims it
            // once for every pager page (same reason History's own title doesn't add one either).
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 2.dp, 16.dp, 0.dp),
        )

        if (!hasAnyChart) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Not enough measurements yet for charts.")
            }
            return@Column
        }

        if (dailyAsiTrend.size >= 2) {
            // Border follows whichever bar is currently selected/shown in the chart below (falls
            // back to today's own status until that first fires, same value this always showed).
            OutlinedCard(
                border = BorderStroke(1.dp, asiBorderColor ?: dailyAsiTrend.last().result.color.toComposeColor()),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 0.dp),
            ) {
                AsiTrendChart(
                    dailyAsiTrend,
                    selectedDate = selectedDate,
                    onSelectedDateChanged = { selectedDate = it },
                    onShownColorChanged = { asiBorderColor = it },
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (dailyTrend.size >= 2) {
            OutlinedCard(
                border = BorderStroke(1.dp, hrvBorderColor ?: MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 0.dp),
            ) {
                HrvTrendChart(
                    dailyTrend,
                    selectedDate = selectedDate,
                    onSelectedDateChanged = { selectedDate = it },
                    onShownColorChanged = { hrvBorderColor = it },
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (dailyRhrTrend.size >= 2) {
            OutlinedCard(
                border = BorderStroke(1.dp, rhrBorderColor ?: RhrBandOverlay),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 16.dp),
            ) {
                RhrTrendChart(
                    dailyRhrTrend,
                    selectedDate = selectedDate,
                    onSelectedDateChanged = { selectedDate = it },
                    onShownColorChanged = { rhrBorderColor = it },
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

package kotobaya.android.shell.api.stats

import kotobaya.android.shell.api.LocalRoute

/** The stats group's local routes (C-31, SHELL.md 4.3). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // LF-A3: Home's summary, heatmap and retention from the replica (StatsRoutes.kt); read only, never the network
    LocalRoute("getStatsSummary", "GET", "/api/v1/stats/summary") { call -> getStatsSummary(call) },
    LocalRoute("getStatsHeatmap", "GET", "/api/v1/stats/heatmap") { call -> getStatsHeatmap(call) },
    LocalRoute("getStatsRetention", "GET", "/api/v1/stats/retention") { call -> getStatsRetention(call) },
)

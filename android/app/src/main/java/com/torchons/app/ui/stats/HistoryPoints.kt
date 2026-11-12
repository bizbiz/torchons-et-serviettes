package com.torchons.app.ui.stats

/**
 * The server writes the points into each completion line of the history feed
 * (`api/stats.py`, templates `completion_solo` / `completion_collective` :
 * « Camille a complété : Aspirateur (+1 pts) », « … (+2 pts chacun) », « … (+2 pts each) »).
 * With gamification hidden for this user (household setting or their own), the app drops that
 * suffix: seen on 2026-11-12 in Stats → Historique of a household with gamification off. The
 * server only knows the household setting, the app knows both.
 */
private val POINTS_SUFFIX = Regex("""\s*\(\+?-?\d+ pts?(?: chacun| each)?\)\s*$""")

internal fun stripPointsSuffix(description: String): String = description.replace(POINTS_SUFFIX, "")

/** [history] as served, or without the points suffix when [gamificationVisible] is false. */
internal fun withoutPoints(history: List<Map<String, Any>>, gamificationVisible: Boolean): List<Map<String, Any>> =
    if (gamificationVisible) history
    else history.map { event ->
        val desc = event["description"] as? String ?: return@map event
        event + ("description" to stripPointsSuffix(desc))
    }

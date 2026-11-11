package com.torchons.app.worker

import com.torchons.app.util.TaskUrgencyCalculator.State
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the daily and overdue reminders (NotificationMath.kt).
 *
 * Paths by which the count or the list can be wrong:
 *  (1) rounding: exact halves, binary noise, below half ............ COVERED
 *  (2) zero / invalid inputs (0 red, ratio 0, negative, NaN) ........ COVERED
 *  (3) default ratio, including 0 members ........................... COVERED
 *  (4) "turned red today" against every state pair .................. COVERED
 *  (5) overdue body: join, cap on titles, long title ................ COVERED
 *  (6) singular/plural text: plurals exist in both locales .......... COVERED (file check)
 *  non couvert : l'etat rouge lui-meme (TaskUrgencyCalculator) et le filtre de visibilite
 *    (WidgetVisibilityTest), REUTILISES et non recodes ici.
 *  non couvert : workers, cache DataStore du ratio et planification, qui exigent Android
 *    (Hilt, WorkManager), non executes en JVM.
 */
class NotificationMathTest {

    @Test fun `21 red tasks at 0_6 give 13`() = assertEquals(13, dailyReminderCount(21, 0.6))

    @Test fun `exact halves round up`() {
        assertEquals(2, dailyReminderCount(3, 0.5))   // 1.5
        assertEquals(2, dailyReminderCount(5, 0.3))   // 1.5 (binary noise above)
        assertEquals(1, dailyReminderCount(1, 0.5))   // 0.5
        assertEquals(3, dailyReminderCount(5, 0.5))   // 2.5
    }

    @Test fun `below half rounds down`() = assertEquals(1, dailyReminderCount(7, 0.2)) // 1.4

    @Test fun `zero cases are silent`() {
        assertEquals(0, dailyReminderCount(0, 0.6))
        assertEquals(0, dailyReminderCount(5, 0.0))
        assertEquals(0, dailyReminderCount(1, 0.4))   // 0.4 rounds to 0
        assertEquals(0, dailyReminderCount(5, -1.0))
        assertEquals(0, dailyReminderCount(5, Double.NaN))
    }

    @Test fun `default ratio is an equal split`() {
        assertEquals(0.5, defaultTargetRatio(2), 1e-12)
        assertEquals(1.0 / 3, defaultTargetRatio(3), 1e-12)
        assertEquals(1.0, defaultTargetRatio(0), 1e-12)
    }

    @Test fun `only tasks red today and not red yesterday are listed`() {
        val states = listOf(
            TaskStatePair("new red", State.RED, State.ORANGE),
            TaskStatePair("new red from green", State.RED, State.GREEN),
            TaskStatePair("still red", State.RED, State.RED),
            TaskStatePair("orange", State.ORANGE, State.GREEN),
            TaskStatePair("done", State.GREEN, State.RED),
        )
        assertEquals(listOf("new red", "new red from green"), newlyRedTitles(states))
        assertEquals(emptyList<String>(), newlyRedTitles(emptyList()))
    }

    @Test fun `overdue body joins titles and truncates`() {
        assertEquals("A, B", overdueBody(listOf("A", "B")))
        assertEquals("1, 2, 3, 4, 5 +2", overdueBody((1..7).map { it.toString() }))
        val cut = overdueBody(listOf("x".repeat(60)))
        assertEquals(40, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    /** Singular and plural live in the plurals resource: both locales need `one` and `other`. */
    @Test fun `daily text has a singular and a plural form in both languages`() {
        for (dir in listOf("values", "values-en")) {
            val xml = File("src/main/res/$dir/strings.xml").readText()
            val plurals = Regex(
                """<plurals name="notif_daily_overdue_text">(.*?)</plurals>""",
                RegexOption.DOT_MATCHES_ALL,
            )
            val block = plurals.find(xml)?.groupValues?.get(1) ?: error("plurals missing in $dir")
            fun item(q: String) = Regex("""quantity="$q">([^<]*)<""").find(block)?.groupValues?.get(1)
                ?: error("$q missing in $dir")
            val one = item("one")
            val other = item("other")
            assertTrue("%d" in one && "%d" in other)
            assertTrue(one != other)
        }
    }
}

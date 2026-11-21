package com.torchons.app.ui.today

import com.torchons.app.R
import com.torchons.app.core.today.TodayTap
import com.torchons.app.core.today.todayTap
import com.torchons.app.data.model.Delegation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What TalkBack hears on a « ! » without visible text (task card, widget). Alice hands the task to
 * Bob: only Alice may cancel it, Bob's tap only shows who handed it (seen on the emulator on
 * 21/11: Bob's red « ! » announced « Annuler »).
 *
 * PATHS by which a « ! » could announce the wrong thing:
 *   (1) the choice itself, grey / red mine / red someone else's (`todayButtonDescription`) .. COVERED
 *   (2) « mine » agrees with what the tap does (`todayTap`: Cancel vs Info) ................ COVERED
 *   non couvert : `todayCardState` setting `mine` (task card), it needs Android Resources for
 *                 its line; the widget's `todayTap == CANCEL`, a Glance composable. Both are read
 *                 by todo-checks/bang-recu-annuler.sh (.prive), which fails if either goes back to
 *                 choosing « Annuler » from `lit` alone.
 *   non couvert : the task page's button, it carries visible text and no description.
 */
class TodayButtonDescriptionTest {
    private val handed = Delegation(
        id = "d-1", taskId = "t-1", authorId = "a", recipientIds = listOf("b"), createdAt = "2026-11-21T08:00:00Z",
    )

    @Test
    fun grey_button_offers_to_hand_the_task() {
        assertEquals(R.string.today_button_cd, todayButtonDescription(lit = false, mine = false))
    }

    @Test
    fun red_button_says_cancel_to_its_author_only() {
        assertTrue(todayTap(handed, emptyList(), "a") is TodayTap.Cancel)
        assertEquals(R.string.today_button_cancel_cd, todayButtonDescription(lit = true, mine = true))
    }

    @Test
    fun red_button_of_a_received_task_says_info_not_cancel() {
        assertTrue(todayTap(handed, emptyList(), "b") is TodayTap.Info)
        assertEquals(R.string.today_button_info_cd, todayButtonDescription(lit = true, mine = false))
    }
}

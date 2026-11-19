package com.torchons.app.ui.help

import androidx.annotation.StringRes
import com.torchons.app.R
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.help.HelpCondition.Always
import com.torchons.app.ui.help.HelpCondition.Gamification
import com.torchons.app.ui.help.HelpCondition.Module
import com.torchons.app.ui.help.HelpCondition.MotivationModule

/*
 * Content of the "?" help sheets: one entry per screen, each with its sections. The texts are in
 * strings.xml (help_<screen>_<n>_title / _text), copied from docs/plans/aide-textes-brouillon.md,
 * which says which module each section describes. Rendering and gating: HelpSheet.kt.
 */

/** When a section is shown. */
internal sealed interface HelpCondition {
    /** Core: always shown. */
    data object Always : HelpCondition

    /** Shown when this household module is on. */
    data class Module(val key: String) : HelpCondition

    /**
     * Points and streaks as the screens show them: module `motivation` on AND the personal
     * "hide points" preference not set (same rule as `gamificationVisibleFlow`).
     */
    data object Gamification : HelpCondition

    /** The `motivation` module alone: the "Points for me" section, where the preference is undone. */
    data object MotivationModule : HelpCondition
}

internal class HelpSection(
    @StringRes val title: Int,
    @StringRes val text: Int,
    val condition: HelpCondition,
)

/** The nine screens that carry the "?" button. */
internal enum class HelpScreen(@StringRes val title: Int, val sections: List<HelpSection>) {
    HOME(
        R.string.help_home_title,
        listOf(
            HelpSection(R.string.help_home_1_title, R.string.help_home_1_text, Always),
            HelpSection(R.string.help_home_2_title, R.string.help_home_2_text, Always),
            HelpSection(R.string.help_home_3_title, R.string.help_home_3_text, Always),
            HelpSection(R.string.help_home_4_title, R.string.help_home_4_text, Always),
            HelpSection(R.string.help_home_5_title, R.string.help_home_5_text, Module(Modules.OBJECTIVES)),
            HelpSection(R.string.help_home_6_title, R.string.help_home_6_text, Gamification),
            HelpSection(R.string.help_home_7_title, R.string.help_home_7_text, Always),
            HelpSection(R.string.help_home_8_title, R.string.help_home_8_text, Module(Modules.OBJECTIVES)),
        ),
    ),
    TASK_LIST(
        R.string.help_task_list_title,
        listOf(
            HelpSection(R.string.help_task_list_1_title, R.string.help_task_list_1_text, Always),
            HelpSection(R.string.help_task_list_2_title, R.string.help_task_list_2_text, Always),
            HelpSection(R.string.help_task_list_3_title, R.string.help_task_list_3_text, Always),
            HelpSection(R.string.help_task_list_4_title, R.string.help_task_list_4_text, Always),
            HelpSection(R.string.help_task_list_5_title, R.string.help_task_list_5_text, Module(Modules.PRESENCE)),
            HelpSection(R.string.help_task_list_6_title, R.string.help_task_list_6_text, Always),
        ),
    ),
    STATS(
        R.string.help_stats_title,
        listOf(
            HelpSection(R.string.help_stats_1_title, R.string.help_stats_1_text, Always),
            HelpSection(R.string.help_stats_2_title, R.string.help_stats_2_text, Module(Modules.OBJECTIVES)),
            HelpSection(R.string.help_stats_3_title, R.string.help_stats_3_text, Module(Modules.OBJECTIVES)),
            HelpSection(R.string.help_stats_4_title, R.string.help_stats_4_text, Module(Modules.REPARTITION)),
            HelpSection(R.string.help_stats_5_title, R.string.help_stats_5_text, Gamification),
            HelpSection(R.string.help_stats_6_title, R.string.help_stats_6_text, Module(Modules.STATISTICS)),
        ),
    ),
    SETTINGS(
        R.string.help_settings_title,
        listOf(
            HelpSection(R.string.help_settings_1_title, R.string.help_settings_1_text, Always),
            HelpSection(R.string.help_settings_2_title, R.string.help_settings_2_text, Always),
            HelpSection(R.string.help_settings_3_title, R.string.help_settings_3_text, MotivationModule),
            HelpSection(R.string.help_settings_4_title, R.string.help_settings_4_text, Module(Modules.REMINDERS)),
            HelpSection(R.string.help_settings_5_title, R.string.help_settings_5_text, Always),
            HelpSection(R.string.help_settings_6_title, R.string.help_settings_6_text, Always),
        ),
    ),
    FOYER_SETTINGS(
        R.string.help_foyer_settings_title,
        listOf(
            HelpSection(R.string.help_foyer_settings_1_title, R.string.help_foyer_settings_1_text, Always),
            HelpSection(R.string.help_foyer_settings_2_title, R.string.help_foyer_settings_2_text, Always),
            HelpSection(R.string.help_foyer_settings_3_title, R.string.help_foyer_settings_3_text, Always),
            HelpSection(R.string.help_foyer_settings_4_title, R.string.help_foyer_settings_4_text, Always),
            HelpSection(
                R.string.help_foyer_settings_5_title, R.string.help_foyer_settings_5_text, Module(Modules.PRESENCE),
            ),
            HelpSection(
                R.string.help_foyer_settings_6_title, R.string.help_foyer_settings_6_text, Module(Modules.OBJECTIVES),
            ),
            HelpSection(
                R.string.help_foyer_settings_7_title, R.string.help_foyer_settings_7_text, Module(Modules.BONUS_MALUS),
            ),
            HelpSection(
                R.string.help_foyer_settings_8_title, R.string.help_foyer_settings_8_text, Module(Modules.REPARTITION),
            ),
            HelpSection(R.string.help_foyer_settings_9_title, R.string.help_foyer_settings_9_text, Always),
        ),
    ),
    TASK_DETAIL(
        R.string.help_task_detail_title,
        listOf(
            HelpSection(R.string.help_task_detail_1_title, R.string.help_task_detail_1_text, Always),
            HelpSection(R.string.help_task_detail_2_title, R.string.help_task_detail_2_text, Always),
            HelpSection(R.string.help_task_detail_3_title, R.string.help_task_detail_3_text, Always),
            HelpSection(R.string.help_task_detail_4_title, R.string.help_task_detail_4_text, Always),
            HelpSection(R.string.help_task_detail_5_title, R.string.help_task_detail_5_text, Always),
            HelpSection(R.string.help_task_detail_6_title, R.string.help_task_detail_6_text, Always),
            HelpSection(R.string.help_task_detail_7_title, R.string.help_task_detail_7_text, Always),
            HelpSection(R.string.help_task_detail_8_title, R.string.help_task_detail_8_text, Always),
        ),
    ),
    TASK_EDIT(
        R.string.help_task_edit_title,
        listOf(
            HelpSection(R.string.help_task_edit_1_title, R.string.help_task_edit_1_text, Always),
            HelpSection(R.string.help_task_edit_2_title, R.string.help_task_edit_2_text, Always),
            HelpSection(R.string.help_task_edit_3_title, R.string.help_task_edit_3_text, Always),
            HelpSection(R.string.help_task_edit_4_title, R.string.help_task_edit_4_text, Always),
            HelpSection(R.string.help_task_edit_5_title, R.string.help_task_edit_5_text, Always),
            HelpSection(R.string.help_task_edit_6_title, R.string.help_task_edit_6_text, Always),
        ),
    ),
    CONTEXTS(
        R.string.help_contexts_title,
        listOf(
            HelpSection(R.string.help_contexts_1_title, R.string.help_contexts_1_text, Always),
            HelpSection(R.string.help_contexts_2_title, R.string.help_contexts_2_text, Module(Modules.PRESENCE)),
            HelpSection(R.string.help_contexts_3_title, R.string.help_contexts_3_text, Always),
        ),
    ),
    DEVICES(
        R.string.help_devices_title,
        listOf(
            HelpSection(R.string.help_devices_1_title, R.string.help_devices_1_text, Always),
            HelpSection(R.string.help_devices_2_title, R.string.help_devices_2_text, Always),
            HelpSection(R.string.help_devices_3_title, R.string.help_devices_3_text, Always),
        ),
    ),
}

package com.torchons.app.ui.common

import androidx.compose.ui.res.stringResource
import com.torchons.app.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Star
import com.torchons.app.data.model.PresenceCellState
import com.torchons.app.data.model.PresenceSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * Grille calendrier de presence MENSUELLE partagee (P2).
 *
 * Utilisee par DEUX surfaces :
 *  - l'ecran Foyer (sous-section "Calendrier de presence", mode editable),
 *  - le StreakCalendarModal (Stats / prompt RESOLVE_PRESENCE, mode editable).
 *
 * Etats de case (decision 4d) :
 *  - present     = vert,
 *  - absent      = rouge DOUX (errorContainer, non agressif — pas un rouge
 *    vif), pour ne pas culpabiliser une absence legitime,
 *  - non renseigne = gris neutre (compte "absent" par defaut cote gel).
 *
 * Icone source en coin de case : Wi-Fi (auto_wifi) / main (manuel). Pas
 * d'icone pour les jours non renseignes.
 *
 * UX d'edition retenue : **cycle a 3 etats au tap** (le plus simple a
 * decouvrir sans geste cache, coherent avec un calendrier ou tout est
 * potentiellement modifiable). Tap successif sur une case :
 *   non renseigne -> present -> absent -> non renseigne -> ...
 * (un long-press serait invisible / non decouvrable ici ; on prefere le
 * cycle visible). En mode lecture seule (`editable = false`) le tap est
 * desactive (reuse future : modal Stats P3).
 *
 * IMPORTANT : ce composable est "controle" — il ne stocke pas l'etat. Le
 * parent fournit `statesByDate` (date ISO -> Pair(state, source)) et recoit
 * `onCellTap(date, newState)` avec le PROCHAIN etat du cycle. Le parent
 * persiste (PUT /presence) puis re-fournit l'etat serveur (online =
 * autoritaire, cf docs/cache-conventions.md). Pas de cache local ici.
 */
@Composable
fun PresenceCalendarGrid(
    /** Mois affiche. */
    visibleMonth: YearMonth,
    /** date ISO "YYYY-MM-DD" -> (etat, source). Jours absents de la map =
     *  non renseignes. */
    statesByDate: Map<String, Pair<PresenceCellState, PresenceSource>>,
    onMonthChange: (YearMonth) -> Unit,
    /** Tap sur une case -> prochain etat du cycle (parent persiste). */
    onCellTap: (date: LocalDate, next: PresenceCellState) -> Unit,
    modifier: Modifier = Modifier,
    /** false = pas d'edition (reuse future modal Stats lecture seule). */
    editable: Boolean = true,
    /** Affiche la legende + (i) sous la grille. */
    showLegend: Boolean = true,
    /**
     * Jours ou le membre selectionne a fait >=1 tache (etoile). Off par
     * defaut : l'editeur de presence (prompt) reste inchange.
     */
    activityDays: Set<LocalDate> = emptySet(),
    /** Affiche l'etoile d'activite + la legende serie. */
    showStreak: Boolean = false,
    /**
     * Liseré serie, source UNIQUE = serveur /streaks (le calcul client
     * fenetré cassait en cross-mois). recordRunDays = run record,
     * currentRunDays = run en cours ; incluent les jours absents pontés.
     */
    recordRunDays: Set<LocalDate> = emptySet(),
    currentRunDays: Set<LocalDate> = emptySet(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MonthHeader(
            visibleMonth = visibleMonth,
            onPrev = { onMonthChange(visibleMonth.minusMonths(1)) },
            onNext = { onMonthChange(visibleMonth.plusMonths(1)) },
        )
        Spacer(Modifier.height(8.dp))

        // En-tetes jours : semaine demarrant lundi (la grille est construite ainsi).
        // Day initials follow the phone's language: they were hardcoded in French
        // and an English phone read « L M M J V S D » (2026-11-17).
        val locale = LocalConfiguration.current.locales[0]
        val dayHeaders = remember(locale) {
            DayOfWeek.values().map { it.getDisplayName(TextStyle.NARROW, locale) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            dayHeaders.forEach { h ->
                Text(
                    h,
                    modifier = Modifier.size(36.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // Grille du mois : on aligne sur le lundi de la 1re semaine et le
        // dimanche de la derniere (cases hors-mois rendues vides).
        val firstOfMonth = visibleMonth.atDay(1)
        val lastOfMonth = visibleMonth.atEndOfMonth()
        val startOfWeek = firstOfMonth.with(DayOfWeek.MONDAY)
        val endOfWeek = lastOfMonth.with(DayOfWeek.SUNDAY)

        var cursor = startOfWeek
        while (cursor <= endOfWeek) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Serie = liseré par case piloté par le serveur (plus de
                // couche connecteur cliente, cf params record/currentRunDays).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (dow in 1..7) {
                        val day = cursor
                        val inMonth = day.month == visibleMonth.month &&
                            day.year == visibleMonth.year
                        if (inMonth) {
                            val key = day.toString()
                            val (state, source) = statesByDate[key]
                                ?: (PresenceCellState.UNKNOWN to PresenceSource.NONE)
                            DayCell(
                                day = day,
                                state = state,
                                source = source,
                                editable = editable,
                                showStar = showStreak && activityDays.contains(day),
                                inRecordRun = recordRunDays.contains(day),
                                inCurrentRun = currentRunDays.contains(day),
                                onTap = {
                                    onCellTap(day, nextState(state))
                                },
                            )
                        } else {
                            // Padding (case hors du mois affiche).
                            Box(modifier = Modifier.size(36.dp))
                        }
                        cursor = cursor.plusDays(1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (showLegend) {
            Spacer(Modifier.height(8.dp))
            PresenceLegend(showStreak = showStreak)
        }
    }
}

/** Cycle a 3 etats : UNKNOWN -> PRESENT -> ABSENT -> UNKNOWN. */
private fun nextState(current: PresenceCellState): PresenceCellState =
    when (current) {
        PresenceCellState.UNKNOWN -> PresenceCellState.PRESENT
        PresenceCellState.PRESENT -> PresenceCellState.ABSENT
        PresenceCellState.ABSENT -> PresenceCellState.UNKNOWN
    }

@Composable
private fun MonthHeader(
    visibleMonth: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    // Month name in the phone's language (was forced to French).
    val locale = LocalConfiguration.current.locales[0]
    val title = visibleMonth.month
        .getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { it.titlecase(locale) } + " ${visibleMonth.year}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.presence_prev_month))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.presence_next_month))
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    state: PresenceCellState,
    source: PresenceSource,
    editable: Boolean,
    onTap: () -> Unit,
    showStar: Boolean = false,
    /** Liseré serie (P4b) : record prime sur en-cours si chevauchement. */
    inRecordRun: Boolean = false,
    inCurrentRun: Boolean = false,
) {
    // Rouge DOUX = errorContainer (teinte douce), pas le rouge vif `error`.
    val presentColor = Color(0xFF10B981)
    val absentColor = MaterialTheme.colorScheme.errorContainer
    val unknownColor = MaterialTheme.colorScheme.surfaceVariant

    val targetBg = when (state) {
        PresenceCellState.PRESENT -> presentColor
        PresenceCellState.ABSENT -> absentColor
        PresenceCellState.UNKNOWN -> unknownColor
    }
    val bg by animateColorAsState(targetBg, label = "presenceCellBg")

    val textColor = when (state) {
        PresenceCellState.PRESENT -> Color.White
        PresenceCellState.ABSENT -> MaterialTheme.colorScheme.onErrorContainer
        PresenceCellState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var cellModifier = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(bg)
    // Liseré serie : record (or, epais) prime sur en-cours (indigo). Le
    // fond de case reste la presence (vert/rouge doux/gris) — decision 5 v3.
    val runColor: Color? = when {
        inRecordRun -> STREAK_RECORD_COLOR
        inCurrentRun -> STREAK_CURRENT_COLOR
        else -> null
    }
    if (runColor != null) {
        cellModifier = cellModifier.border(
            width = if (inRecordRun) 3.dp else 2.dp,
            color = runColor,
            shape = RoundedCornerShape(8.dp),
        )
    }
    if (editable) {
        cellModifier = cellModifier.clickable { onTap() }
    }

    Box(modifier = cellModifier, contentAlignment = Alignment.Center) {
        Text(
            "${day.dayOfMonth}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
        // Icone source en haut a droite (pas d'icone si non renseigne).
        val sourceIcon: ImageVector? = when (source) {
            PresenceSource.AUTO_WIFI -> Icons.Default.Wifi
            PresenceSource.MANUAL -> Icons.Default.TouchApp
            PresenceSource.NONE -> null
        }
        if (sourceIcon != null && state != PresenceCellState.UNKNOWN) {
            Icon(
                imageVector = sourceIcon,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(1.dp)
                    .size(10.dp),
            )
        }
        // P3 — Etoile : ce membre a fait >=1 tache ce jour. Coin haut-gauche
        // (l'icone source occupe le coin haut-droit). Couleur ambre, visible
        // sur les 3 fonds de case.
        if (showStar) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = stringResource(R.string.presence_day_activity),
                tint = STREAK_STAR_COLOR,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(1.dp)
                    .size(11.dp),
            )
        }
    }
}

/** Or — etoile d'activite + liseré du run RECORD (best_streak). Lisible
 *  sur les 3 fonds de presence (vert / rouge doux / gris). */
private val STREAK_STAR_COLOR = Color(0xFFF59E0B)
private val STREAK_RECORD_COLOR = STREAK_STAR_COLOR

/** Indigo — liseré du run EN COURS (current_streak). Distinct de l'or
 *  (record) et des couleurs de presence. */
private val STREAK_CURRENT_COLOR = Color(0xFF6366F1)

// StreakRender / computeStreakRender / StreakConnectorCell supprimés : le
// calcul client fenetré cassait en cross-mois. Serie 100 % serveur
// (_compute_user_streak -> /streaks), rendue en liseré par DayCell.

/** Legende des 3 etats + 2 icones source + (i) detaillee. En mode streak,
 *  ajoute la ligne etoile + l'explication de la regle pont/cassure. */
@Composable
private fun PresenceLegend(showStreak: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.presence_legend_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // (i) detaillee. La partie streak n'est ajoutee qu'en mode streak (modal Stats).
            val streakInfo = if (showStreak) stringResource(R.string.presence_help_streak) else ""
            InfoTooltip(
                title = stringResource(
                    if (showStreak) R.string.presence_help_title_streak else R.string.presence_help_title
                ),
                description = stringResource(R.string.presence_help_body) + streakInfo,
            )
        }
        Spacer(Modifier.height(6.dp))
        LegendChip(
            color = Color(0xFF10B981),
            label = stringResource(R.string.presence_legend_present),
        )
        LegendChip(
            color = MaterialTheme.colorScheme.errorContainer,
            label = stringResource(R.string.presence_legend_absent),
        )
        LegendChip(
            color = MaterialTheme.colorScheme.surfaceVariant,
            label = stringResource(R.string.presence_legend_unknown),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.presence_legend_auto),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.presence_legend_manual),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // P4b — etoile + liseré record/en-cours (mode serie : modal).
        if (showStreak) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = STREAK_STAR_COLOR,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.presence_legend_task_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            LegendBorderChip(
                color = STREAK_RECORD_COLOR,
                label = stringResource(R.string.presence_legend_record),
            )
            LegendBorderChip(
                color = STREAK_CURRENT_COLOR,
                label = stringResource(R.string.presence_legend_current),
            )
        }
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pastille à liseré (pas remplie) — miroir du liseré serie d'une case. */
@Composable
private fun LegendBorderChip(color: Color, label: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(2.dp, color, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

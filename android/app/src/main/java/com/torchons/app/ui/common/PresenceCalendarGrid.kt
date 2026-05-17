package com.torchons.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import java.util.Locale

/**
 * Grille calendrier de presence MENSUELLE partagee (P2 —
 * design-foyer-settings-presence.md decision 4c/4d).
 *
 * Utilisee par DEUX surfaces :
 *  - l'ecran Foyer (sous-section "Calendrier de presence", mode editable),
 *  - le prompt RESOLVE_PRESENCE (ResolvePresenceDialog, day grid editable).
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
     * P3 — Couche etoile/streak (design decision 6). Jours ou le membre
     * selectionne a fait >=1 tache. Off par defaut : l'editeur de presence
     * P2 (ecran Foyer / prompt) reste visuellement INCHANGE.
     */
    activityDays: Set<LocalDate> = emptySet(),
    /** Active le rendu etoiles + serie reliee. false = comportement P2 strict. */
    showStreak: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MonthHeader(
            visibleMonth = visibleMonth,
            onPrev = { onMonthChange(visibleMonth.minusMonths(1)) },
            onNext = { onMonthChange(visibleMonth.plusMonths(1)) },
        )
        Spacer(Modifier.height(8.dp))

        // En-tetes jours : semaine demarrant lundi (FR).
        val dayHeaders = listOf("L", "M", "M", "J", "V", "S", "D")
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

        // P3 — Pre-calcul de la couche streak : pour chaque jour AFFICHE
        // (y compris les jours hors-mois des semaines de bord, pour que la
        // serie ne soit pas coupee artificiellement au saut de semaine), on
        // determine s'il est un noeud actif (etoile) et si la transition
        // vers le jour suivant est "reliee".
        val streak = if (showStreak) {
            computeStreakRender(startOfWeek, endOfWeek, statesByDate, activityDays)
        } else {
            null
        }

        var cursor = startOfWeek
        while (cursor <= endOfWeek) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Couche connecteur (sous les cases) : un ruban horizontal
                // qui relie les jours d'une meme serie. Dessine d'abord pour
                // passer DERRIERE les cases (z-order = ordre de declaration).
                if (streak != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        var connCursor = cursor
                        for (dow in 1..7) {
                            val d = connCursor
                            // Reliure entrante (depuis le jour precedent) et
                            // sortante (vers le jour suivant) : un segment
                            // est trace si la transition correspondante est
                            // "reliee" (adjacence active OU pont sur absents).
                            val linkLeft = streak.linkedToPrev.contains(d)
                            val linkRight = streak.linkedToNext.contains(d)
                            val node = streak.activeNodes.contains(d)
                            StreakConnectorCell(
                                drawHalo = node,
                                linkLeft = linkLeft,
                                linkRight = linkRight,
                            )
                            connCursor = connCursor.plusDays(1)
                        }
                    }
                }
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
    val title = visibleMonth.month
        .getDisplayName(TextStyle.FULL, Locale.FRENCH)
        .replaceFirstChar { it.uppercase() } + " ${visibleMonth.year}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Mois precedent")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Mois suivant")
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
                contentDescription = "Activite ce jour",
                tint = STREAK_STAR_COLOR,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(1.dp)
                    .size(11.dp),
            )
        }
    }
}

/** Ambre — etoile d'activite + halo de serie (lisible sur vert/rouge/gris). */
private val STREAK_STAR_COLOR = Color(0xFFF59E0B)

/**
 * Resultat du pre-calcul de la couche streak pour une plage de jours.
 *
 * - `activeNodes` : jours ou le membre a fait >=1 tache (etoile + halo).
 * - `linkedToNext` / `linkedToPrev` : un jour `d` est dedans si la transition
 *   `d -> d+1` (resp. `d-1 -> d`) appartient a une serie continue et doit
 *   etre tracee comme un trait reliant les deux cases.
 */
private data class StreakRender(
    val activeNodes: Set<LocalDate>,
    val linkedToNext: Set<LocalDate>,
    val linkedToPrev: Set<LocalDate>,
)

/**
 * Calcule la couche streak sur [from, to] (regle v0.17 — design decision 6).
 *
 * Un "noeud actif" = jour present dans `activityDays` (>=1 tache faite par le
 * membre selectionne).
 *
 * Reliure de deux noeuds actifs A < B consecutifs (aucun autre noeud actif
 * entre les deux) : on regarde chaque jour de gap strictement entre A et B :
 *  - **PONT** (la serie continue, regle v0.17) si TOUS les jours de gap sont
 *    `confirmed_absent` (ABSENT explicite dans `statesByDate`). Un jour
 *    absent ne casse pas la serie : le membre etait legitimement parti.
 *  - **CASSURE** (vraie rupture) des qu'UN jour de gap est :
 *      * incertain = aucune ligne de presence (etat UNKNOWN), OU
 *      * `confirmed_present` mais SANS activite (present a la maison mais
 *        rien fait ce jour-la -> la serie se casse comme un jour normal
 *        sans completion).
 * Deux noeuds actifs strictement adjacents (B = A+1, pas de gap) sont
 * toujours relies.
 */
private fun computeStreakRender(
    from: LocalDate,
    to: LocalDate,
    statesByDate: Map<String, Pair<PresenceCellState, PresenceSource>>,
    activityDays: Set<LocalDate>,
): StreakRender {
    val nodes = mutableSetOf<LocalDate>()
    var d = from
    while (d <= to) {
        if (activityDays.contains(d)) nodes.add(d)
        d = d.plusDays(1)
    }
    if (nodes.size < 1) {
        return StreakRender(nodes, emptySet(), emptySet())
    }

    val sortedNodes = nodes.sorted()
    val linkedToNext = mutableSetOf<LocalDate>()
    val linkedToPrev = mutableSetOf<LocalDate>()

    for (i in 0 until sortedNodes.size - 1) {
        val a = sortedNodes[i]
        val b = sortedNodes[i + 1]
        var bridged = true
        var gap = a.plusDays(1)
        while (gap < b) {
            val state = statesByDate[gap.toString()]?.first
                ?: PresenceCellState.UNKNOWN
            // Pont uniquement si le jour de gap est ABSENT confirme. Tout
            // autre cas (UNKNOWN incertain, ou PRESENT sans activite) casse.
            if (state != PresenceCellState.ABSENT) {
                bridged = false
                break
            }
            gap = gap.plusDays(1)
        }
        if (bridged) {
            // Marque toute la chaine a..b comme reliee (trait continu qui
            // traverse les jours de gap pontes).
            var cur = a
            while (cur < b) {
                linkedToNext.add(cur)
                linkedToPrev.add(cur.plusDays(1))
                cur = cur.plusDays(1)
            }
        }
    }
    return StreakRender(nodes, linkedToNext, linkedToPrev)
}

/**
 * Une case de la couche connecteur (sous la grille). Dessine un ruban
 * horizontal ambre clair :
 *  - un demi-segment vers la gauche si `linkLeft` (relie au jour precedent),
 *  - un demi-segment vers la droite si `linkRight` (relie au jour suivant),
 *  - un point de halo central si `drawHalo` (jour actif).
 * Une CASSURE = absence de segment entre deux cases (rien n'est dessine),
 * d'ou une coupure visuelle nette.
 */
@Composable
private fun StreakConnectorCell(
    drawHalo: Boolean,
    linkLeft: Boolean,
    linkRight: Boolean,
) {
    val bandColor = STREAK_STAR_COLOR.copy(alpha = 0.30f)
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Demi-segment gauche : part du bord gauche jusqu'au centre.
        if (linkLeft) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(20.dp)
                    .height(8.dp)
                    .background(bandColor),
            )
        }
        // Demi-segment droit : part du centre jusqu'au bord droit.
        if (linkRight) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(20.dp)
                    .height(8.dp)
                    .background(bandColor),
            )
        }
        // Halo central (jour actif) — pastille plus marquee derriere la case.
        if (drawHalo) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(STREAK_STAR_COLOR.copy(alpha = 0.22f)),
            )
        }
    }
}

/** Legende des 3 etats + 2 icones source + (i) detaillee. En mode streak,
 *  ajoute la ligne etoile + l'explication de la regle pont/cassure. */
@Composable
private fun PresenceLegend(showStreak: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Legende",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // (i) detaillee. La partie streak n'est ajoutee qu'en mode streak
            // (modal Stats) — l'editeur de presence P2 garde son texte exact.
            val streakInfo = if (showStreak) {
                "\n\n— Serie d'activite —\n" +
                    "L'etoile signale que ce membre a fait au moins une tache " +
                    "ce jour-la. Le halo ambre relie les jours actifs en une " +
                    "serie continue. La serie PASSE AU TRAVERS des jours " +
                    "absents (un jour confirme absent ne casse pas la serie, " +
                    "regle v0.17 : le membre etait legitimement parti). Elle " +
                    "SE CASSE sur un jour incertain (presence non renseignee) " +
                    "ou sur un jour present sans activite (a la maison mais " +
                    "aucune tache faite)."
            } else {
                ""
            }
            InfoTooltip(
                title = if (showStreak) {
                    "Calendrier de presence & series"
                } else {
                    "Calendrier de presence"
                },
                description =
                    "Ce calendrier dit a l'app quels jours quelqu'un etait au " +
                        "foyer. Il sert au gel des taches \"a la maison\" : " +
                        "pendant une absence, ces taches arretent de vieillir.\n\n" +
                        "• Vert = present : journee confirmee a la maison.\n" +
                        "• Rouge doux = absent : journee confirmee hors du foyer " +
                        "(les taches maison sont gelees ce jour-la).\n" +
                        "• Gris = non renseigne : AUCUNE info pour ce jour. Par " +
                        "defaut, un jour non renseigne est COMPTE COMME ABSENT " +
                        "(le foyer reste gele). C'est ce qui pouvait donner des " +
                        "surprises type \"Aspirateur dans 6 j\" : pose " +
                        "explicitement les jours presents pour corriger.\n\n" +
                        "Sources : l'icone Wi-Fi marque un jour detecte " +
                        "automatiquement ; l'icone main un jour pose " +
                        "manuellement. Priorite : manuel > auto Wi-Fi — un " +
                        "jour pose a la main n'est jamais ecrase par la " +
                        "detection auto.\n\n" +
                        "La detection automatique est facultative : elle " +
                        "s'appuie sur le(s) WiFi maison declares juste " +
                        "au-dessus, dans ce meme ecran (section Presence & " +
                        "absences). Sans WiFi configure, renseigne les jours " +
                        "a la main ici." +
                        streakInfo,
            )
        }
        Spacer(Modifier.height(6.dp))
        LegendChip(
            color = Color(0xFF10B981),
            label = "Present",
        )
        LegendChip(
            color = MaterialTheme.colorScheme.errorContainer,
            label = "Absent (taches maison gelees ce jour)",
        )
        LegendChip(
            color = MaterialTheme.colorScheme.surfaceVariant,
            label = "Non renseigne (compte absent par defaut)",
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
                "Detecte automatiquement (WiFi maison)",
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
                "Renseigne manuellement",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // P3 — Ligne etoile/serie (uniquement en mode streak : modal Stats).
        if (showStreak) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = STREAK_STAR_COLOR,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Etoile = tache faite ce jour ; la serie passe au travers " +
                        "des jours absents (v0.17), se casse sur un jour " +
                        "incertain ou present sans activite",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

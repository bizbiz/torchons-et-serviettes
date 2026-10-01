package com.torchons.app.util

import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import com.torchons.app.R
import com.torchons.app.data.model.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.round

/**
 * Calcul d'urgence des taches — modele deterministe base sur la due_date.
 *
 * Remplace l'ancien FreshnessCalculator (decroissance exponentielle) depuis
 * v0.10.0. Voir CLAUDE.md section "Urgence" pour la specification.
 *
 * Modele :
 * - due_date = last_completed + intervalle (ou created_at si jamais faite)
 * - Etat GREEN/ORANGE/RED en fonction de l'ecart today <-> due_date
 * - Fenetre orange = floor(intervalle * 0.10) jours avant l'echeance
 *   - 7j -> 0 (orange uniquement le jour due_date)
 *   - 30j -> 3 (orange a J-3 ... J)
 *   - 60j -> 6 (orange a J-6 ... J)
 * - Tolerance (STRICT/NORMAL/SOUPLE) n'a aucun impact ici : c'est uniquement
 *   un tier de priorite pour le selecteur "la plus urgente" cote backend.
 */
object TaskUrgencyCalculator {

    enum class State { GREEN, ORANGE, RED }

    // Couleurs partagees (utilisees aussi dans le reste de l'UI comme constantes
    // theme-independantes vert/orange/rouge).
    val VERT = Color(0xFF10B981)
    val ORANGE = Color(0xFFF97316)
    val ROUGE = Color(0xFFEF4444)

    /**
     * Intervalle nominal entier en jours d'une recurrence (v0.27 : 3 types).
     *
     * - interval : every_n_days
     * - monthly_variable : moyenne des valeurs (fallback 30)
     * - uncertain : 0 (etat toujours Vert)
     * - null (ponctuelle) : 0 -> pas de fenetre orange
     */
    fun nominalIntervalDays(rule: RecurrenceRule?): Int {
        if (rule == null) return 0
        return when (rule.type) {
            "interval" -> (rule.everyNDays ?: 1).coerceAtLeast(1)
            "monthly_variable" -> {
                val vals = rule.daysPerMonth?.values?.filter { it > 0 } ?: emptyList()
                if (vals.isNotEmpty()) maxOf(1, vals.sum() / vals.size) else 30
            }
            // Mirror of recurrence.py: gap between two occurrences. `rint`
            // rounds half to even like Python's `round` (365 / 2 -> 182).
            "calendar" -> {
                val n = rule.months?.count { it in 1..12 } ?: 0
                if (n > 0) maxOf(1, Math.rint(365.0 / n).toInt()) else 0
            }
            // Mirror of recurrence.py: the period in days (12 -> 365, 6 -> 182).
            "anniversary" -> maxOf(1, Math.rint(anniversaryMonths(rule) * 365.0 / 12).toInt())
            "uncertain" -> 0
            else -> 0
        }
    }

    /**
     * A date-anchored rule (`calendar`, `anniversary`) warns at most this many
     * days ahead (decided 2026-09-30).
     */
    const val CALENDAR_ORANGE_WINDOW_CAP_DAYS = 7

    /** Upper bound on the orange window, null if unbounded. Mirror of recurrence.py. */
    fun orangeWindowCapOf(rule: RecurrenceRule?): Int? =
        if (rule?.type == "calendar" || rule?.type == "anniversary") CALENDAR_ORANGE_WINDOW_CAP_DAYS else null

    /** Orange window in days, cap applied: the one [computeState] uses. */
    private fun orangeWindowDays(rule: RecurrenceRule?): Int {
        val window = floor(nominalIntervalDays(rule) * 0.10).toInt().coerceAtLeast(0)
        val cap = orangeWindowCapOf(rule) ?: return window
        return minOf(window, cap)
    }

    private fun anniversaryMonths(rule: RecurrenceRule): Int = (rule.everyNMonths ?: 12).coerceIn(1, 1200)

    /** Days after the due date before RED. Only `calendar` declares any. */
    fun graceDaysOf(rule: RecurrenceRule?): Int = (rule?.graceDays ?: 0).coerceAtLeast(0)

    /** True si la regle est de type 'uncertain' (cadence libre, v0.14). */
    fun isUncertain(rule: RecurrenceRule?): Boolean = rule?.type == "uncertain"

    /**
     * Calcule la due_date deterministe d'une tache.
     *
     * - Jamais completee : created_at.date()
     * - Ponctuelle deja faite : null (plus d'echeance a surveiller)
     * - Recurrente : derive du pattern + lastCompletedAt
     */
    fun computeDueDate(
        lastCompletedAt: String?,
        rule: RecurrenceRule?,
        createdAt: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        /** Earliest live completion: the `anniversary` anchor, ignored otherwise. */
        firstCompletedAt: String? = null,
    ): LocalDate? {
        // uncertain : aucune echeance visible (v0.14, Q2). L'etat reste GREEN.
        if (isUncertain(rule)) return null

        if (lastCompletedAt == null) {
            return parseDate(createdAt, zone)
        }
        if (rule == null) return null

        val lastDate = parseDate(lastCompletedAt, zone) ?: return null

        return when (rule.type) {
            "interval" -> lastDate.plusDays((rule.everyNDays ?: 1).toLong())

            "monthly_variable" -> {
                // v0.27 : on utilise la valeur du mois courant si dispo,
                // sinon la moyenne (via nominalIntervalDays). Plus precis
                // que la moyenne pour les cadences saisonnieres.
                val currentMonth = lastDate.monthValue.toString()
                val n = rule.daysPerMonth?.get(currentMonth)
                    ?: nominalIntervalDays(rule)
                if (n > 0) lastDate.plusDays(n.toLong()) else null
            }

            "calendar" -> nextCalendarDue(rule, lastDate, today)

            // Without a known first completion the last one stands in for it,
            // like compute_due_date: one period after, never "no due date".
            "anniversary" -> nextAnniversaryDue(rule, parseDate(firstCompletedAt, zone) ?: lastDate, lastDate)

            else -> null
        }
    }

    /**
     * Calendar anchoring, mirror of `next_due` in recurrence.py: the latest
     * PAST occurrence stays due until honoured, then the next one is aimed at.
     * The due date never depends on when the task was done — no drift.
     * A completion honours an occurrence once its orange window has opened:
     * done on 10 May for the 15th, the task does not turn red on the 16th.
     */
    private fun nextCalendarDue(rule: RecurrenceRule, lastDate: LocalDate, today: LocalDate): LocalDate? {
        val months = rule.months?.filter { it in 1..12 }?.takeIf { it.isNotEmpty() } ?: listOf(1)
        val day = (rule.day ?: 1).coerceIn(1, 28)
        // today.year + 2: a yearly deadline honoured early (30 Dec for 1 Jan)
        // aims at the year after next.
        val candidates = (today.year - 1..today.year + 2).flatMap { y ->
            months.map { m -> LocalDate.of(y, m, day) }
        }.sorted()
        val window = orangeWindowDays(rule).toLong()
        val start = candidates.filter { !it.isAfter(today) }.maxOrNull() ?: today
        return candidates.firstOrNull { !it.isBefore(start) && lastDate.isBefore(it.minusDays(window)) }
    }

    /**
     * Anniversary anchoring, mirror of `next_anniversary_due` in recurrence.py.
     *
     * Occurrences are `first + k * everyNMonths` (k >= 1), each computed from
     * the anchor (`plusMonths` clamps to the month's end, a 31st does not decay
     * to the 28th). The due one is the earliest occurrence the last completion
     * has not honoured; a completion honours an occurrence once its orange
     * window has opened. Pure: no clock.
     */
    fun nextAnniversaryDue(rule: RecurrenceRule, first: LocalDate, last: LocalDate): LocalDate {
        val n = anniversaryMonths(rule).toLong()
        val window = orangeWindowDays(rule).toLong()
        val monthsBetween = (last.year - first.year) * 12L + (last.monthValue - first.monthValue)
        var k = maxOf(1L, Math.floorDiv(monthsBetween, n))
        while (k > 1 && first.plusMonths((k - 1) * n).minusDays(window).isAfter(last)) k--
        while (!first.plusMonths(k * n).minusDays(window).isAfter(last)) k++
        return first.plusMonths(k * n)
    }

    /**
     * Date effective utilisee pour evaluer l'urgence : `today - absentDays`.
     *
     * Home freeze v2 : si la tache appartient a une categorie `requires_home`
     * et que `home_freeze_enabled` est actif au niveau foyer, le backend
     * renseigne `absent_days_since_last_completion` sur la tache. On recule
     * `today` du meme nombre de jours pour que les absences ne consomment
     * pas l'intervalle de la tache. Sinon, `absentDays=0` => no-op.
     */
    fun effectiveToday(today: LocalDate, absentDays: Int): LocalDate =
        if (absentDays > 0) today.minusDays(absentDays.toLong()) else today

    /**
     * Etat d'urgence (GREEN / ORANGE / RED) selon today <-> due_date.
     *
     * - today > due_date : RED
     * - due_date - window <= today <= due_date : ORANGE
     * - today < due_date - window : GREEN
     *
     * window = floor(nominalInterval * 0.10), min 0.
     * Si dueDate est null (ponctuelle deja faite) -> GREEN.
     *
     * `absentDays` (home freeze v2) decale `today` en arriere : utile pour les
     * categories `requires_home` quand le foyer a confirme des jours absents.
     */
    fun computeState(
        dueDate: LocalDate?,
        today: LocalDate,
        nominalInterval: Int,
        absentDays: Int = 0,
        graceDays: Int = 0,
        windowCapDays: Int? = null,
    ): State {
        if (dueDate == null) return State.GREEN
        val effToday = effectiveToday(today, absentDays)
        // `graceDays` only delays RED: the task is flagged at its due date.
        if (effToday.isAfter(dueDate.plusDays(graceDays.coerceAtLeast(0).toLong()))) return State.RED
        var window = floor(nominalInterval * 0.10).toInt().coerceAtLeast(0)
        if (windowCapDays != null) window = minOf(window, windowCapDays.coerceAtLeast(0))
        val orangeStart = dueDate.minusDays(window.toLong())
        return if (!effToday.isBefore(orangeStart)) State.ORANGE else State.GREEN
    }

    /**
     * Nombre de jours d'ici a l'echeance (negatif si en retard, 0 si aujourd'hui).
     *
     * `absentDays` (home freeze v2) decale `today` en arriere.
     */
    fun daysUntilDue(dueDate: LocalDate?, today: LocalDate, absentDays: Int = 0): Int {
        if (dueDate == null) return 0
        val effToday = effectiveToday(today, absentDays)
        return (dueDate.toEpochDay() - effToday.toEpochDay()).toInt()
    }

    /** Libelle localise du countdown ("dans 3 j", "à faire aujourd'hui", etc.). */
    fun countdownLabel(
        res: Resources,
        dueDate: LocalDate?,
        today: LocalDate,
        absentDays: Int = 0,
    ): String {
        if (dueDate == null) return ""
        return countdownLabelFromDays(res, daysUntilDue(dueDate, today, absentDays))
    }

    /** Variante prenant directement le delta en jours (utile pour SuggestionCard). */
    fun countdownLabelFromDays(res: Resources, daysUntilDue: Int): String = when {
        daysUntilDue > 1 -> res.getString(R.string.countdown_in_days_format, daysUntilDue)
        daysUntilDue == 1 -> res.getString(R.string.countdown_tomorrow)
        daysUntilDue == 0 -> res.getString(R.string.countdown_today)
        daysUntilDue == -1 -> res.getString(R.string.countdown_one_day_late)
        else -> res.getString(R.string.countdown_days_late_format, -daysUntilDue)
    }

    /**
     * Libelle factuel localise pour une tache 'uncertain' (v0.14, Q9).
     * Utilise pour les cartes de la 3e section "Sans cadence" du home.
     *
     * - null lastCompletedAt : "Pas encore faite"
     * - delta <= 0 : "Fait aujourd'hui"
     * - delta == 1 : "Fait hier"
     * - sinon : "Fait il y a N j"
     */
    fun uncertainCountdownLabel(
        res: Resources,
        lastCompletedAt: String?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (lastCompletedAt == null) return res.getString(R.string.uncertain_not_done)
        val lastDate = parseDate(lastCompletedAt, zone)
            ?: return res.getString(R.string.uncertain_not_done)
        val delta = (today.toEpochDay() - lastDate.toEpochDay()).toInt()
        return when {
            delta <= 0 -> res.getString(R.string.uncertain_done_today)
            delta == 1 -> res.getString(R.string.uncertain_done_yesterday)
            else -> res.getString(R.string.uncertain_done_days_ago_format, delta)
        }
    }

    /** Couleur correspondant a un nom d'etat sous forme String ("GREEN"/"ORANGE"/"RED"). */
    fun stateColor(stateName: String): Color = when (stateName) {
        "RED" -> ROUGE
        "ORANGE" -> ORANGE
        else -> VERT
    }

    /** Couleur correspondant a un etat d'urgence. */
    fun stateColor(state: State): Color = when (state) {
        State.GREEN -> VERT
        State.ORANGE -> ORANGE
        State.RED -> ROUGE
    }

    /** Ordre de tri par urgence (les plus urgents d'abord). RED=0, ORANGE=1, GREEN=2. */
    fun urgencyOrder(state: State): Int = when (state) {
        State.RED -> 0
        State.ORANGE -> 1
        State.GREEN -> 2
    }

    /**
     * Reconcilie la derniere completion SERVEUR (TaskResponse.last_completed_at,
     * autoritaire en ligne) avec la plus recente du cache LOCAL des completions
     * (optimiste hors-ligne). On garde la plus RECENTE des deux :
     *
     * - en ligne : le serveur est a jour (>= local) -> le serveur fait foi ;
     * - hors-ligne : une completion locale optimiste pas encore refletee par
     *   un refresh /tasks est plus recente que le champ serveur perime ->
     *   le local gagne (recalcul immediat sans reseau).
     *
     * Resout le cas "cache de completions vide pour la tache" (ex. ecran liste
     * apres un refresh /tasks qui n'a pas (re)peuple /completions) ou le client
     * retombait a tort sur created_at -> "dans Xj" au lieu de Rouge.
     * Cf docs/cache-conventions.md (regle 2 : online = serveur autoritaire).
     */
    fun latestCompletedAt(serverLastCompletedAt: String?, localCacheLatest: String?): String? {
        val s = parseInstant(serverLastCompletedAt)
        val l = parseInstant(localCacheLatest)
        return when {
            s == null -> localCacheLatest
            l == null -> serverLastCompletedAt
            l.isAfter(s) -> localCacheLatest
            else -> serverLastCompletedAt
        }
    }

    /**
     * Earliest completion: the SERVER value (TaskResponse.first_completed_at)
     * and the earliest of the LOCAL cache, the older of the two. The local one
     * wins when an offline completion (possibly backdated) is not yet known to
     * the server. Counterpart of [latestCompletedAt].
     */
    fun earliestCompletedAt(serverFirstCompletedAt: String?, localCacheEarliest: String?): String? {
        val s = parseInstant(serverFirstCompletedAt)
        val l = parseInstant(localCacheEarliest)
        return when {
            s == null -> localCacheEarliest
            l == null -> serverFirstCompletedAt
            l.isBefore(s) -> localCacheEarliest
            else -> serverFirstCompletedAt
        }
    }

    private fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso)
        } catch (e: Exception) {
            try {
                Instant.parse("${iso}Z")
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun parseDate(iso: String?, zone: ZoneId): LocalDate? {
        return parseInstant(iso)?.atZone(zone)?.toLocalDate()
    }
}

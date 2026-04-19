package com.torchons.app.util

import androidx.compose.ui.graphics.Color
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
 * v0.10.0. Voir CLAUDE.md section "Freshness (P2)" pour la specification.
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
     * Intervalle nominal entier en jours d'une recurrence.
     *
     * Utilise pour dimensionner la fenetre orange. Pour les recurrences
     * non-interval, on renvoie une constante par tache (predictible).
     *
     * - interval : every_n_days
     * - weekdays : max(1, round(7 / nb_jours))
     * - monthly : 30
     * - custom : every_n_weeks * 7
     * - monthly_variable : moyenne des valeurs (fallback 30)
     * - null (ponctuelle) : 0 -> pas de fenetre orange
     */
    fun nominalIntervalDays(rule: RecurrenceRule?): Int {
        if (rule == null) return 0
        return when (rule.type) {
            "interval" -> (rule.everyNDays ?: 1).coerceAtLeast(1)
            "weekdays" -> {
                val n = rule.days?.size ?: 0
                if (n > 0) maxOf(1, round(7.0 / n).toInt()) else 0
            }
            "monthly" -> 30
            "custom" -> ((rule.everyNWeeks ?: 1) * 7).coerceAtLeast(1)
            "monthly_variable" -> {
                val vals = rule.daysPerMonth?.values?.filter { it > 0 } ?: emptyList()
                if (vals.isNotEmpty()) maxOf(1, vals.sum() / vals.size) else 30
            }
            // uncertain : pas de fenetre orange (v0.14, Q2 etat toujours Vert)
            "uncertain" -> 0
            else -> 0
        }
    }

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

            "weekdays" -> {
                val days = rule.days ?: return null
                if (days.isEmpty()) return null
                var candidate = lastDate.plusDays(1)
                for (i in 0..7) {
                    if (days.contains(candidate.dayOfWeek.value)) return candidate
                    candidate = candidate.plusDays(1)
                }
                null
            }

            "monthly" -> {
                val dom = rule.dayOfMonth ?: 1
                var year = lastDate.year
                var month = lastDate.monthValue
                if (lastDate.dayOfMonth >= dom) {
                    month++
                    if (month > 12) {
                        month = 1
                        year++
                    }
                }
                val firstOfMonth = LocalDate.of(year, month, 1)
                val maxDay = firstOfMonth.lengthOfMonth()
                LocalDate.of(year, month, minOf(dom, maxDay))
            }

            "custom" -> {
                val nWeeks = (rule.everyNWeeks ?: 1).coerceAtLeast(1)
                val day = rule.day ?: 1
                val target = lastDate.plusWeeks(nWeeks.toLong())
                val diff = ((day - target.dayOfWeek.value) % 7 + 7) % 7
                target.plusDays(diff.toLong())
            }

            "monthly_variable" -> {
                val n = nominalIntervalDays(rule)
                if (n > 0) lastDate.plusDays(n.toLong()) else null
            }

            else -> null
        }
    }

    /**
     * Etat d'urgence (GREEN / ORANGE / RED) selon today <-> due_date.
     *
     * - today > due_date : RED
     * - due_date - window <= today <= due_date : ORANGE
     * - today < due_date - window : GREEN
     *
     * window = floor(nominalInterval * 0.10), min 0.
     * Si dueDate est null (ponctuelle deja faite) -> GREEN.
     */
    fun computeState(
        dueDate: LocalDate?,
        today: LocalDate,
        nominalInterval: Int,
    ): State {
        if (dueDate == null) return State.GREEN
        if (today.isAfter(dueDate)) return State.RED
        val window = floor(nominalInterval * 0.10).toInt().coerceAtLeast(0)
        val orangeStart = dueDate.minusDays(window.toLong())
        return if (!today.isBefore(orangeStart)) State.ORANGE else State.GREEN
    }

    /** Nombre de jours d'ici a l'echeance (negatif si en retard, 0 si aujourd'hui). */
    fun daysUntilDue(dueDate: LocalDate?, today: LocalDate): Int {
        if (dueDate == null) return 0
        return (dueDate.toEpochDay() - today.toEpochDay()).toInt()
    }

    /** Libelle FR humain du countdown ("dans 3 j", "à faire aujourd'hui", etc.). */
    fun countdownLabel(dueDate: LocalDate?, today: LocalDate): String {
        if (dueDate == null) return ""
        return countdownLabelFromDays(daysUntilDue(dueDate, today))
    }

    /** Variante prenant directement le delta en jours (utile pour SuggestionCard). */
    fun countdownLabelFromDays(daysUntilDue: Int): String = when {
        daysUntilDue > 1 -> "dans $daysUntilDue j"
        daysUntilDue == 1 -> "demain"
        daysUntilDue == 0 -> "à faire aujourd'hui"
        daysUntilDue == -1 -> "en retard d'1 j"
        else -> "en retard de ${-daysUntilDue} j"
    }

    /**
     * Libelle factuel FR pour une tache 'uncertain' (v0.14, Q9).
     * Utilise pour les cartes de la 3e section "Sans cadence" du home.
     *
     * - null lastCompletedAt : "Pas encore faite"
     * - delta <= 0 : "Fait aujourd'hui"
     * - delta == 1 : "Fait hier"
     * - sinon : "Fait il y a N j"
     */
    fun uncertainCountdownLabel(
        lastCompletedAt: String?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (lastCompletedAt == null) return "Pas encore faite"
        val lastDate = parseDate(lastCompletedAt, zone) ?: return "Pas encore faite"
        val delta = (today.toEpochDay() - lastDate.toEpochDay()).toInt()
        return when {
            delta <= 0 -> "Fait aujourd'hui"
            delta == 1 -> "Fait hier"
            else -> "Fait il y a $delta j"
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

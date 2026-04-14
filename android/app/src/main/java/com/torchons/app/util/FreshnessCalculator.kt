package com.torchons.app.util

import androidx.compose.ui.graphics.Color
import com.torchons.app.data.model.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * Calculateur de fraicheur pour les taches menageres.
 *
 * La fraicheur represente a quel point une tache est "recente" par rapport
 * a sa derniere completion. Elle decroit exponentiellement avec le temps.
 *
 * Formule : freshness = 100 * exp(-0.693 * heures_depuis / half_life_hours)
 *
 * La demi-vie est derivee de l'intervalle nominal de la recurrence,
 * multipliee par le facteur de tolerance (STRICT x0.5, NORMAL x1, SOUPLE x2).
 *
 * Niveaux de fraicheur :
 * - Vert (>=80%) : la tache est recente
 * - Jaune (>=50%) : la tache commence a vieillir
 * - Orange (>=25%) : la tache a besoin d'attention
 * - Rouge (<25%) : la tache est urgente
 */
object FreshnessCalculator {

    // Couleurs de fraicheur
    val VERT = Color(0xFF10B981)
    val JAUNE = Color(0xFFF59E0B)
    val ORANGE = Color(0xFFF97316)
    val ROUGE = Color(0xFFEF4444)

    private val TOLERANCE_MULTIPLIER = mapOf(
        "STRICT" to 0.5,
        "NORMAL" to 1.0,
        "SOUPLE" to 2.0,
    )

    /**
     * Calcule l'intervalle nominal en heures d'une recurrence.
     * Equivalent Kotlin de `interval_hours` cote backend.
     */
    fun intervalHours(rule: RecurrenceRule?): Double {
        if (rule == null) return Double.POSITIVE_INFINITY
        return when (rule.type) {
            "interval" -> (rule.everyNDays ?: 1) * 24.0
            "weekdays" -> {
                val n = rule.days?.size ?: 0
                if (n > 0) (7.0 / n) * 24.0 else Double.POSITIVE_INFINITY
            }
            "monthly" -> 30.44 * 24.0
            "custom" -> (rule.everyNWeeks ?: 1) * 7 * 24.0
            "monthly_variable" -> {
                val month = LocalDate.now().monthValue.toString()
                val n = rule.daysPerMonth?.get(month)
                if (n != null && n > 0) n * 24.0 else 30.44 * 24.0
            }
            else -> Double.POSITIVE_INFINITY
        }
    }

    /**
     * Calcule le pourcentage de fraicheur d'une tache.
     *
     * @param lastCompletedAt Date/heure de la derniere completion (format ISO 8601), ou null.
     * @param rule Regle de recurrence (sert a deriver l'intervalle nominal).
     * @param tolerance Tolerance au retard ("STRICT", "NORMAL", "SOUPLE").
     * @return Pourcentage de fraicheur entre 0 et 100.
     */
    fun calculateFreshness(
        lastCompletedAt: String?,
        rule: RecurrenceRule?,
        tolerance: String,
    ): Double {
        val base = intervalHours(rule)
        if (!base.isFinite() || base <= 0.0) {
            // Ponctuelle (sans recurrence) : 0% tant que pas faite, 100% une fois faite.
            return if (lastCompletedAt == null) 0.0 else 100.0
        }
        val halfLife = base * (TOLERANCE_MULTIPLIER[tolerance] ?: 1.0)
        if (halfLife <= 0.0) {
            return if (lastCompletedAt == null) 0.0 else 100.0
        }

        if (lastCompletedAt == null) return 0.0

        val completedInstant = try {
            Instant.parse(lastCompletedAt)
        } catch (e: Exception) {
            // Essayer de parser sans le Z final ou avec d'autres formats
            try {
                Instant.parse("${lastCompletedAt}Z")
            } catch (e2: Exception) {
                return 0.0
            }
        }

        val now = Instant.now()
        val hoursSince = ChronoUnit.MINUTES.between(completedInstant, now) / 60.0

        if (hoursSince < 0) return 100.0 // Completion dans le futur = fraicheur maximale

        return (100.0 * exp(-0.693 * hoursSince / halfLife)).coerceIn(0.0, 100.0)
    }

    /**
     * Retourne la couleur correspondant au niveau de fraicheur.
     */
    fun getFreshnessColor(freshnessPercent: Double): Color {
        return when {
            freshnessPercent >= 80.0 -> VERT
            freshnessPercent >= 50.0 -> JAUNE
            freshnessPercent >= 25.0 -> ORANGE
            else -> ROUGE
        }
    }

    /**
     * Retourne le libelle du niveau de fraicheur.
     */
    fun getFreshnessLabel(freshnessPercent: Double): String {
        return when {
            freshnessPercent >= 80.0 -> "Vert"
            freshnessPercent >= 50.0 -> "Jaune"
            freshnessPercent >= 25.0 -> "Orange"
            else -> "Rouge"
        }
    }

    /**
     * Retourne l'ordre de tri par urgence (les plus urgents d'abord).
     * 0 = Rouge (le plus urgent), 3 = Vert (le moins urgent).
     */
    fun getUrgencyOrder(freshnessPercent: Double): Int {
        return when {
            freshnessPercent < 25.0 -> 0  // Rouge
            freshnessPercent < 50.0 -> 1  // Orange
            freshnessPercent < 80.0 -> 2  // Jaune
            else -> 3                      // Vert
        }
    }
}

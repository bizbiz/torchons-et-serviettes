package com.torchons.app.util

import androidx.compose.ui.graphics.Color
import java.time.Instant
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

    /**
     * Calcule le pourcentage de fraicheur d'une tache.
     *
     * @param lastCompletedAt Date/heure de la derniere completion (format ISO 8601), ou null.
     * @param halfLifeHours Demi-vie en heures (temps pour que la fraicheur passe a 50%).
     * @return Pourcentage de fraicheur entre 0 et 100.
     */
    fun calculateFreshness(lastCompletedAt: String?, halfLifeHours: Double): Double {
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

        return 100.0 * exp(-0.693 * hoursSince / halfLifeHours)
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

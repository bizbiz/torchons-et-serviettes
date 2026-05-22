package com.torchons.app.util

import android.content.res.Resources
import androidx.annotation.StringRes
import com.torchons.app.R

/**
 * Format intelligent d'une duree en jours pour l'affichage utilisateur.
 *
 * Convention : 1 mois = 30 j (approximation visuelle, on ne fait pas de
 * calendrier reel ici), 1 semaine = 7 j. Le but est de remplacer "92 j"
 * par "3 mois 2 j" et "10 j" par "1 sem. 3 j".
 *
 * Choix d'unite (par taille decroissante) :
 *  - >= 30 j : mois + reste en jours (ex: 92 j -> "3 mois 2 j",
 *    30 j -> "1 mois", 45 j -> "1 mois 15 j")
 *  - >= 14 j : semaines + reste en jours (ex: 14 j -> "2 sem.")
 *  - >= 7 j  : "1 semaine" + reste en jours (ex: 10 j -> "1 sem. 3 j")
 *  - < 7 j   : jours seuls (ex: 5 j -> "5 j")
 *
 * L'argument `long` distingue affichage compact ("j" / "sem." / "mois") du
 * long ("jour(s)" / "semaine(s)" / "mois"). Les libelles sont localises via
 * `Resources` (cles `duration_*` dans strings.xml).
 */
object DurationFormat {

    fun formatDaysHuman(res: Resources, days: Int, long: Boolean = false): String {
        if (days <= 0) return res.getString(
            if (long) R.string.duration_zero_long else R.string.duration_zero_short
        )

        val dayLabel: (Int) -> String = { n ->
            if (long) res.getString(
                if (n == 1) R.string.duration_long_day_singular
                else R.string.duration_long_day_plural
            ) else res.getString(R.string.duration_short_day)
        }
        val weekLabel: (Int) -> String = { n ->
            if (long) res.getString(
                if (n == 1) R.string.duration_long_week_singular
                else R.string.duration_long_week_plural
            ) else res.getString(R.string.duration_short_week)
        }
        val monthLabel: (Int) -> String = { n ->
            if (long) res.getString(
                if (n == 1) R.string.duration_long_month_singular
                else R.string.duration_long_month_plural
            ) else res.getString(R.string.duration_short_month)
        }

        return when {
            days < 7 -> "$days ${dayLabel(days)}"
            days < 14 -> {
                val rem = days - 7
                if (rem == 0) "1 ${weekLabel(1)}"
                else "1 ${weekLabel(1)} $rem ${dayLabel(rem)}"
            }
            days < 30 -> {
                val w = days / 7
                val rem = days % 7
                if (rem == 0) "$w ${weekLabel(w)}"
                else "$w ${weekLabel(w)} $rem ${dayLabel(rem)}"
            }
            else -> {
                val m = days / 30
                val rem = days % 30
                if (rem == 0) "$m ${monthLabel(m)}"
                else "$m ${monthLabel(m)} $rem ${dayLabel(rem)}"
            }
        }
    }
}

/**
 * Unite de saisie d'une duree exprimee en jours. Permet a l'utilisateur
 * de remplir un nombre de mois/semaines/jours dans le formulaire ; la
 * conversion vers le nombre de jours stocke se fait via [toDays] et
 * l'inverse via [fromDays] (avec arrondi a l'entier le plus proche).
 *
 * 1 mois = 30 j, 1 semaine = 7 j (cf. [DurationFormat]).
 *
 * Le libelle d'affichage est une `@StringRes` (cles
 * `duration_unit_*_label` dans strings.xml) pour permettre l'i18n
 * (l'enum lui-meme reste FR pour eviter de churn les call sites du
 * type `DurationUnit.JOURS`).
 */
enum class DurationUnit(@StringRes val labelRes: Int, val days: Int) {
    JOURS(R.string.duration_unit_days_label, 1),
    SEMAINES(R.string.duration_unit_weeks_label, 7),
    MOIS(R.string.duration_unit_months_label, 30);

    fun toDays(value: Int): Int = (value * days).coerceAtLeast(1)

    /**
     * Valeur entiere a afficher dans cette unite pour un nombre total
     * de jours donne. Arrondi au plus proche (ex: 10 j -> 1 semaine,
     * 11 j -> 2 semaines), avec un minimum de 1.
     */
    fun fromDays(totalDays: Int): Int {
        if (days == 1) return totalDays.coerceAtLeast(1)
        val raw = totalDays.toFloat() / days
        return raw.roundToIntHalfUp().coerceAtLeast(1)
    }

    companion object {
        /**
         * Choisit l'unite la plus naturelle pour exprimer un nombre de
         * jours (sans perte). Si le total est multiple de 30 et >= 30,
         * on prend Mois. Sinon, multiple de 7 et >= 7 -> Semaines. Sinon,
         * Jours. Sert a initialiser le segmented button selon la valeur
         * existante de la tache.
         */
        fun bestFor(totalDays: Int): DurationUnit = when {
            totalDays >= 30 && totalDays % 30 == 0 -> MOIS
            totalDays >= 7 && totalDays % 7 == 0 -> SEMAINES
            else -> JOURS
        }
    }
}

private fun Float.roundToIntHalfUp(): Int = kotlin.math.floor(this + 0.5f).toInt()

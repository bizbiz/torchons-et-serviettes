package com.torchons.app.util

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
 * Pour un usage "long" (`long = true`), on ecrit "mois", "semaine(s)",
 * "jour(s)". Pour un usage "court" (defaut), on ecrit "mois", "sem.",
 * "j". Le pluriel sur "jour" et "semaine" suit la regle francaise
 * (>= 2 -> -s, mois invariable).
 */
object DurationFormat {

    fun formatDaysHuman(days: Int, long: Boolean = false): String {
        if (days <= 0) return if (long) "0 jour" else "0 j"
        val jLabel = if (long) (if (days == 1) "jour" else "jours") else "j"
        val sLabel: (Int) -> String = { n -> if (long) (if (n == 1) "semaine" else "semaines") else "sem." }
        val mLabel = "mois"

        return when {
            days < 7 -> "$days $jLabel"
            days < 14 -> {
                val rem = days - 7
                if (rem == 0) "1 ${sLabel(1)}"
                else "1 ${sLabel(1)} ${rem} ${if (long) (if (rem == 1) "jour" else "jours") else "j"}"
            }
            days < 30 -> {
                val w = days / 7
                val rem = days % 7
                if (rem == 0) "$w ${sLabel(w)}"
                else "$w ${sLabel(w)} ${rem} ${if (long) (if (rem == 1) "jour" else "jours") else "j"}"
            }
            else -> {
                val m = days / 30
                val rem = days % 30
                if (rem == 0) "$m $mLabel"
                else "$m $mLabel ${rem} ${if (long) (if (rem == 1) "jour" else "jours") else "j"}"
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
 */
enum class DurationUnit(val label: String, val days: Int) {
    JOURS("Jours", 1),
    SEMAINES("Semaines", 7),
    MOIS("Mois", 30);

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

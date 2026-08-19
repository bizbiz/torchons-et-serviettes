package com.torchons.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.torchons.app.R
import com.torchons.app.data.model.RecurrenceRule

/**
 * Traduit une regle de recurrence en une phrase qui explique le COMPORTEMENT,
 * pas seulement la valeur.
 *
 * « tous les 7 jours » ne dit pas si l'echeance glisse quand on fait la tache
 * en retard — et c'est precisement ce qui distingue les types. La phrase le
 * dit, avec un exemple chiffre :
 *
 *   interval  « … Si vous la faites au 10e jour, elle reviendra au 17e. »
 *   calendar  « … peu importe la date de la derniere execution. »
 *
 * Rendu cote CLIENT et non serveur : l'ecran d'edition doit fonctionner
 * hors-ligne (cf. docs/cache-conventions.md), donc le texte vient de
 * strings.xml et suit la locale de l'appareil sans aller-retour reseau.
 */
@Composable
fun recurrenceSentence(rule: RecurrenceRule?): String =
    recurrenceSentence(rule, LocalContext.current)

fun recurrenceSentence(rule: RecurrenceRule?, context: Context): String {
    val res = context.resources
    if (rule == null) return res.getString(R.string.recurrence_sentence_one_shot)

    return when (rule.type) {
        "interval" -> {
            val n = rule.everyNDays ?: 1
            if (n == 1) {
                res.getString(R.string.recurrence_sentence_daily)
            } else {
                // L'exemple prend un jour EN RETARD (n+3) et non le jour
                // d'echeance : c'est le glissement qu'il faut montrer, et il
                // ne se voit pas si l'exemple est a l'heure. Pour 7 jours :
                // « faites-la au 10e, elle revient au 17e ».
                val enRetard = n + 3
                res.getString(
                    R.string.recurrence_sentence_interval_format,
                    n, enRetard, enRetard + n,
                )
            }
        }

        "monthly_variable" -> {
            val values = rule.daysPerMonth?.values?.filter { it > 0 }.orEmpty()
            if (values.isEmpty()) {
                res.getString(R.string.recurrence_sentence_unknown)
            } else {
                res.getString(
                    R.string.recurrence_sentence_monthly_variable_format,
                    values.min(), values.max(),
                )
            }
        }

        "calendar" -> {
            val months = rule.months.orEmpty().sorted()
            val day = rule.day ?: 1
            if (months.isEmpty()) {
                res.getString(R.string.recurrence_sentence_unknown)
            } else {
                val dates = months.joinToString(
                    separator = res.getString(R.string.recurrence_sentence_list_separator),
                ) { m ->
                    // Le premier du mois porte son ordinal en francais
                    // (« le 1er mai »), pas les autres jours.
                    if (day == 1) {
                        res.getString(
                            R.string.recurrence_sentence_date_first_format,
                            monthName(res, m),
                        )
                    } else {
                        res.getString(
                            R.string.recurrence_sentence_date_format,
                            day, monthName(res, m),
                        )
                    }
                }
                val base = res.getString(R.string.recurrence_sentence_calendar_format, dates)
                val grace = rule.graceDays ?: 0
                if (grace > 0) {
                    base + " " + res.getString(R.string.recurrence_sentence_grace_format, grace)
                } else {
                    base
                }
            }
        }

        "uncertain" -> res.getString(R.string.recurrence_sentence_uncertain)

        else -> res.getString(R.string.recurrence_sentence_unknown)
    }
}

private fun monthName(res: android.content.res.Resources, month: Int): String {
    val names = res.getStringArray(R.array.month_names)
    return names.getOrElse(month - 1) { "?" }
}

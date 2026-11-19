package com.torchons.app.ui.taskedit

import com.torchons.app.data.model.CatalogSuggestion
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.User
import com.torchons.app.ui.common.UserError
import com.torchons.app.util.DurationUnit

/**
 * Etat du formulaire de creation/edition de tache.
 */
data class TaskEditUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isEditMode: Boolean = false,
    val title: String = "",
    val description: String = "",
    /** Contextes choisis, dans l'ORDRE : le premier decide de l'endroit ou la
     *  tache s'affiche. Une liste et non un Set pour cette raison. */
    val contextIds: List<String> = emptyList(),
    val assignedToIds: List<String> = emptyList(),
    val recurrenceType: String = "none",
    /**
     * Texte affiche dans le champ "Tous les X" — exprime la valeur dans
     * l'unite [recurrenceUnit] courante (ex: "3" en MOIS = 90 jours).
     * La conversion vers le canonique en jours se fait au save() via
     * [recurrenceUnit].toDays(...).
     */
    val recurrenceFieldText: String = "7",
    /** Unite de saisie pour le champ de recurrence (Jours / Semaines / Mois). */
    val recurrenceUnit: DurationUnit = DurationUnit.JOURS,
    /** `calendar` : mois ou la tache devient due. Vide = janvier par defaut. */
    val recurrenceMonths: Set<Int> = emptySet(),
    /** `calendar` : jour dans le mois (1..28). */
    val recurrenceDay: Int = 1,
    /**
     * `calendar` : tolerance en jours apres l'echeance avant le rouge. C'est
     * ce qui exprime « acceptable jusqu'a la fin de l'ete » sans creer une
     * seconde echeance.
     */
    val recurrenceGraceDays: Int = 0,
    /** `anniversary` : period amount, in years or months per [anniversaryInYears]. */
    val anniversaryAmount: Int = 1,
    /** `anniversary` : true = the amount counts years, false = months. */
    val anniversaryInYears: Boolean = true,
    val daysPerMonth: Map<String, String> = (1..12).associate { it.toString() to "" },
    val weight: Int = 1,
    val tolerance: String = "NORMAL",
    val contexts: List<ContextView> = emptyList(),
    /** Propositions du catalogue pertinentes pour ce foyer. */
    val suggestions: List<CatalogSuggestion> = emptyList(),
    /** Titre de la proposition appliquee, pour l'afficher et pouvoir l'annuler. */
    val appliedSuggestion: String? = null,
    val users: List<User> = emptyList(),
    val error: UserError? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    /**
     * Etat des votes de frequence pour la tache en cours d'edition.
     * Non-null uniquement en mode edition + type interval. Si votesCount >= 1,
     * le champ recurrenceFieldText est grise dans l'UI et seul le mini-dialog
     * "Modifier via le vote" peut le faire bouger.
     */
    val frequencyVotes: FrequencyVotesState? = null,
    /** Affiche le mini-dialog de re-vote frequence. */
    val showFrequencyVoteDialog: Boolean = false,
    /**
     * Titre de la tache source quand on est en mode "creation par clonage"
     * (via bouton Dupliquer dans TaskDetail ou selection d'une suggestion
     * d'autocomplete). Non-null = un chip "Duplique de [titre]" est affiche
     * en haut du formulaire et l'autocomplete est desactive le temps que
     * l'user n'ait pas reset le clone.
     */
    val clonedFromTitle: String? = null,
)

/** `anniversary` period in months, as stored (`every_n_months`). */
fun TaskEditUiState.anniversaryEveryNMonths(): Int =
    anniversaryAmount.coerceAtLeast(1) * (if (anniversaryInYears) 12 else 1)

/** Editor fields of a stored `anniversary` rule: whole years when possible. */
internal fun anniversaryFieldsOf(rule: RecurrenceRule?): Pair<Int, Boolean> {
    val months = rule?.everyNMonths?.coerceAtLeast(1) ?: 12
    return if (months % 12 == 0) (months / 12) to true else months to false
}

/** Duree canonique en jours derivee de [recurrenceFieldText] et [recurrenceUnit]. */
fun TaskEditUiState.canonicalEveryNDays(): Int {
    val raw = recurrenceFieldText.toIntOrNull() ?: 7
    return recurrenceUnit.toDays(raw)
}

/**
 * La regle telle qu'elle serait enregistree maintenant, pour l'apercu.
 *
 * Construite ici et non dans l'ecran pour qu'il n'existe qu'UN endroit qui
 * sache traduire l'etat en regle : la phrase affichee decrit alors exactement
 * ce que la sauvegarde produira, jamais une approximation qui divergerait.
 */
fun TaskEditUiState.previewRecurrenceRule(): RecurrenceRule? = when (recurrenceType) {
    "interval" -> RecurrenceRule(type = "interval", everyNDays = canonicalEveryNDays())
    "monthly_variable" -> RecurrenceRule(
        type = "monthly_variable",
        daysPerMonth = daysPerMonth
            .filter { (it.value.toIntOrNull() ?: 0) > 0 }
            .mapValues { it.value.toInt() },
    )
    "uncertain" -> RecurrenceRule(type = "uncertain")
    "calendar" -> RecurrenceRule(
        type = "calendar",
        months = recurrenceMonths.sorted().ifEmpty { listOf(1) },
        day = recurrenceDay,
        graceDays = recurrenceGraceDays,
    )
    "anniversary" -> RecurrenceRule(type = "anniversary", everyNMonths = anniversaryEveryNMonths())
    else -> null
}

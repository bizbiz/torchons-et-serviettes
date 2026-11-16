package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Registre des modules — decouverte progressive (cf. docs/module-registry.md).
 *
 * Retourne par GET /api/modules : le registre localise (serveur) + l'etat
 * effectif du module pour cet appelant dans le foyer courant. Le contenu
 * (titre, pitch, details) vient de modules.yaml cote serveur, PAS de
 * strings.xml : c'est la meme source qui alimente l'ecran Fonctionnalites,
 * le texte des propositions contextuelles et, en P5, les cartes du site.
 */
data class ModuleView(
    @SerializedName("key") val key: String,
    /** "collective" (decision du foyer) ou "personal" (decision de ce membre). */
    @SerializedName("scope") val scope: String,
    @SerializedName("order") val order: Int,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("title") val title: String,
    @SerializedName("pitch") val pitch: String,
    @SerializedName("details") val details: String,
    @SerializedName("example") val example: String? = null,
    @SerializedName("requires") val requires: List<String> = emptyList(),
    /** Modules requis mais pas encore actifs : non vide = activation refusee. */
    @SerializedName("unmet_requires") val unmetRequires: List<String> = emptyList(),
    /** "signal", "usage" ou "none". */
    @SerializedName("trigger_kind") val triggerKind: String = "none",
    @SerializedName("sub_options") val subOptions: List<SubOptionView> = emptyList(),
    // Guide page fields. Defaults keep the app working against a server
    // that does not send them yet. Strings are nullable on purpose: Gson
    // bypasses Kotlin defaults, so an absent field arrives as null, not "".
    @SerializedName("guide_why") val guideWhy: String? = null,
    @SerializedName("guide_how") val guideHow: String? = null,
    /** This member has already seen the guide (server-side, per user). */
    @SerializedName("guide_seen") val guideSeen: Boolean = false,
    /** Show the guide at next app open (module enabled by another member). */
    @SerializedName("guide_due") val guideDue: Boolean = false,
    /**
     * Who made the last change of this COLLECTIVE module, and when (ISO-8601,
     * UTC). Set by the server only when that member is someone else than the
     * caller; null otherwise (and for an old server).
     */
    @SerializedName("changed_by_name") val changedByName: String? = null,
    @SerializedName("changed_at") val changedAt: String? = null,
) {
    val isCollective: Boolean get() = scope == "collective"
}

data class SubOptionView(
    @SerializedName("key") val key: String,
    @SerializedName("scope") val scope: String,
    @SerializedName("title") val title: String,
    /**
     * Chemin `table.colonne` de l'etat existant de cette sous-option, quand
     * il y en a un. Sert a savoir quel reglage deja en place l'ecran doit
     * afficher sous ce module, au lieu d'en inventer un nouveau.
     */
    @SerializedName("state_field") val stateField: String? = null,
)

data class ModulesResponse(
    @SerializedName("modules") val modules: List<ModuleView> = emptyList(),
)

data class ModuleToggleRequest(
    @SerializedName("enabled") val enabled: Boolean,
)

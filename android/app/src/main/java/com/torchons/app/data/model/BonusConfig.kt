package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Config bonus/malus par foyer — seuils, plancher, params analyse retard.
 * Correspond a la table foyer_bonus_config cote backend.
 * Editable via le panneau 'Options avancees' des Reglages.
 */
data class BonusConfig(
    @SerializedName("seuil_malus_strict") val seuilMalusStrict: Float = 0.10f,
    @SerializedName("seuil_malus_normal") val seuilMalusNormal: Float = 0.30f,
    @SerializedName("seuil_malus_souple") val seuilMalusSouple: Float = 0.50f,
    @SerializedName("seuil_bonus_strict") val seuilBonusStrict: Float = 0.10f,
    @SerializedName("seuil_bonus_normal") val seuilBonusNormal: Float = 0.20f,
    @SerializedName("seuil_bonus_souple") val seuilBonusSouple: Float = 0.40f,
    @SerializedName("plancher_malus") val plancherMalus: Float = 0.50f,
    @SerializedName("bonus_pct") val bonusPct: Float = 0.50f,
    @SerializedName("bump_interval_factor_k") val bumpIntervalFactorK: Float = 3.0f,
    @SerializedName("bump_y_min_days") val bumpYMinDays: Int = 90,
    @SerializedName("bump_y_max_days") val bumpYMaxDays: Int = 1095,
    @SerializedName("bump_z_min") val bumpZMin: Int = 3,
    @SerializedName("bump_x_ratio") val bumpXRatio: Float = 0.70f,
    @SerializedName("bump_grace_ratio") val bumpGraceRatio: Float = 0.70f,
)

package com.torchons.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Porte de session pour les popups d'accueil (notation affinite/effort).
 *
 * Armee au demarrage du process et a chaque ON_RESUME de l'activite — donc
 * au cold start ET au retour depuis le background. Elle n'est PAS rearmee
 * par une navigation inter-ecrans, parce que le lifecycle de l'activite ne
 * repasse pas par ON_RESUME dans ce cas. Desarmee des que la popup est
 * affichee (ou submit/dismiss) pour empecher sa reapparition a chaque refresh.
 */
@Singleton
class PopupSessionGate @Inject constructor() {
    private val _armed = MutableStateFlow(true)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    fun arm() { _armed.value = true }
    fun disarm() { _armed.value = false }
}

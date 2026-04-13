package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/**
 * Couche de confetti plein ecran a placer en overlay (Box) sur les ecrans
 * qui veulent feter une validation de tache. Le confetti tombe pendant
 * environ 2 secondes puis s'arrete naturellement.
 *
 * Utilisation : englober le contenu de l'ecran dans un Box, et placer ce
 * composable en deuxieme enfant. Le `key` doit etre incremente a chaque
 * declenchement pour que konfetti relance l'animation depuis le debut.
 */
@Composable
fun CelebrationOverlay(
    isCelebrating: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isCelebrating) return

    val party = remember {
        Party(
            speed = 0f,
            maxSpeed = 30f,
            damping = 0.9f,
            spread = 360,
            colors = listOf(
                0xFF10B981.toInt(), // Vert (FreshnessCalculator.VERT)
                0xFFF59E0B.toInt(), // Jaune
                0xFF6366F1.toInt(), // Primary indigo
                0xFFEC4899.toInt(), // Rose
                0xFFF97316.toInt(), // Orange
            ),
            position = Position.Relative(0.5, 0.3),
            emitter = Emitter(duration = 200, TimeUnit.MILLISECONDS).max(150),
        )
    }
    KonfettiView(
        modifier = modifier.fillMaxSize(),
        parties = listOf(party),
    )
}

/**
 * Contenu d'une card en mode "celebration" — remplace temporairement le
 * contenu normal apres qu'une tache vient d'etre marquee comme faite.
 *
 * Utilise dans SuggestionCard, InProgressCard, TaskCard. Les emoji et le
 * texte changent legerement selon le contexte (peut etre customise via
 * les parametres `emoji` et `text`).
 *
 * La hauteur s'aligne par defaut sur celle d'une card normale via padding.
 */
@Composable
fun CelebrationCardContent(
    modifier: Modifier = Modifier,
    emoji: String = "🎉",
    text: String = "Bravo !",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Variantes d'emoji pour varier le ressenti. Pickee aleatoirement par celebration. */
val CELEBRATION_EMOJIS = listOf("🎉", "🎊", "🥳", "✨", "👏", "💪", "⭐", "🏆")
val CELEBRATION_TEXTS = listOf(
    "Bravo !",
    "Bien joue !",
    "Top !",
    "Genial !",
    "Tu deboites !",
    "Une de plus !",
)

/** Helper : retourne une paire emoji + texte aleatoire pour une celebration. */
fun randomCelebration(): Pair<String, String> =
    CELEBRATION_EMOJIS.random() to CELEBRATION_TEXTS.random()

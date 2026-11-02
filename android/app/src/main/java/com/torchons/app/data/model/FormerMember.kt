package com.torchons.app.data.model

/**
 * A member who left the household. [anonymized] false = still inside the 30-day
 * return window (real name shown, struck through); true = name erased server side.
 */
data class FormerMember(
    val id: String,
    val username: String,
    val displayName: String,
    val anonymized: Boolean,
    val leftAt: String?,
)

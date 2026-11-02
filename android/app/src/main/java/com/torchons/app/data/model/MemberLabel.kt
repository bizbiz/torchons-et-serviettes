package com.torchons.app.data.model

/** What to print for a member id, and whether the name is struck through. */
data class MemberLabel(val text: String, val struck: Boolean)

/**
 * Resolves a member id to its display label.
 *  - active member: plain name;
 *  - former member inside the return window: real name, struck through;
 *  - anonymized former member: [formerLabel], not struck;
 *  - unknown id: "?" (unchanged legacy fallback).
 */
fun resolveMemberLabel(
    id: String,
    activeNames: Map<String, String>,
    former: Map<String, FormerMember>,
    formerLabel: String,
): MemberLabel {
    activeNames[id]?.let { return MemberLabel(it, struck = false) }
    val f = former[id] ?: return MemberLabel("?", struck = false)
    return if (f.anonymized) MemberLabel(formerLabel, struck = false)
    else MemberLabel(f.displayName, struck = true)
}

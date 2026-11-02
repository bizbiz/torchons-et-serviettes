package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.FormerMember

/**
 * A member who left the household. Kept in its OWN table, never in `users`:
 * `users` feeds every selector and every "whole household" computation, and a
 * former member must appear in none of them.
 */
@Entity(tableName = "former_members")
data class FormerMemberEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val anonymized: Boolean,
    val leftAt: String?,
)

fun FormerMember.toEntity(): FormerMemberEntity =
    FormerMemberEntity(id, username, displayName, anonymized, leftAt)

fun FormerMemberEntity.toDomain(): FormerMember =
    FormerMember(id, username, displayName, anonymized, leftAt)

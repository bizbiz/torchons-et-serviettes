package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.FormerMemberDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.model.User
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Household members, split in two caches on purpose:
 *  - `users`: ACTIVE members, the only list selectors and "whole household"
 *    computations may read;
 *  - `former_members`: those who left, read only to label an author.
 */
@Singleton
class MemberRepository @Inject constructor(
    private val apiService: ApiService,
    private val userDao: UserDao,
    private val formerMemberDao: FormerMemberDao,
) {
    fun observeUsers(): Flow<List<User>> =
        userDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeFormerMembers(): Flow<List<FormerMember>> =
        formerMemberDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** REPLACE of both scopes on a successful answer (cache-conventions rule 3). */
    suspend fun refreshUsers(): Boolean {
        return try {
            val response = apiService.getUsers(includeFormer = true)
            if (response.isSuccessful) {
                val (former, active) = response.body().orEmpty().partition { it.leftAt != null }
                userDao.replaceAll(active.map { it.toEntity() })
                formerMemberDao.replaceAll(former.map { it.toFormerMember().toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun wipe() {
        userDao.replaceAll(emptyList())
        formerMemberDao.replaceAll(emptyList())
    }
}

/** A user row carrying `left_at` is a former member; a missing flag counts as not anonymized. */
internal fun User.toFormerMember() = FormerMember(
    id = id,
    username = username,
    displayName = displayName,
    anonymized = anonymized ?: false,
    leftAt = leftAt,
)

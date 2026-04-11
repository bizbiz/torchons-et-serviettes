package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CompleteTaskRequest
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository gerant les taches, categories et completions.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val apiService: ApiService
) {
    /** Recupere la liste des taches avec filtres optionnels. */
    suspend fun getTasks(
        categoryId: String? = null,
        activeOnly: Boolean? = true
    ): Result<List<Task>> {
        return try {
            val response = apiService.getTasks(categoryId, activeOnly)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Erreur lors du chargement des taches: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Cree une nouvelle tache. */
    suspend fun createTask(request: CreateTaskRequest): Result<Task> {
        return try {
            val response = apiService.createTask(request)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erreur lors de la creation: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Met a jour une tache existante. */
    suspend fun updateTask(taskId: String, request: UpdateTaskRequest): Result<Task> {
        return try {
            val response = apiService.updateTask(taskId, request)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erreur lors de la mise a jour: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Supprime une tache (soft delete). */
    suspend fun deleteTask(taskId: String): Result<Unit> {
        return try {
            val response = apiService.deleteTask(taskId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Erreur lors de la suppression: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Recupere la liste des categories. */
    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = apiService.getCategories()
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Erreur lors du chargement des categories: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Marque une tache comme completee. */
    suspend fun completeTask(
        taskId: String,
        completedAt: String? = null,
        completedBy: String? = null,
        notes: String? = null
    ): Result<Completion> {
        return try {
            val response = apiService.completeTask(taskId, CompleteTaskRequest(notes = notes, completedAt = completedAt, completedBy = completedBy))
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erreur lors de la completion: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Recupere la liste des completions avec filtres optionnels. */
    suspend fun getCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null
    ): Result<List<Completion>> {
        return try {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Erreur lors du chargement des completions: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Liste tous les utilisateurs du foyer. */
    suspend fun getUsers(): Result<List<User>> {
        return try {
            val response = apiService.getUsers()
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Erreur lors du chargement des utilisateurs: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

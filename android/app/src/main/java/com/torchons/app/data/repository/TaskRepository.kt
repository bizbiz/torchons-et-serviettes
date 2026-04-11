package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CompleteTaskRequest
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository des taches, categories, completions, users — stale-while-revalidate.
 *
 * Pattern :
 * - Les `observe*` retournent un Flow depuis Room : affichage instantane
 *   meme si l'app est offline ou vient juste de demarrer.
 * - Les `refresh*` font le fetch API + upsert dans Room. Le Flow emet
 *   automatiquement les nouvelles donnees. Echec silencieux : si l'API ne
 *   repond pas, le cache reste ce qu'il etait.
 * - Les ecritures (create/update/delete/complete) tapent l'API directement
 *   et invalident le cache local apres succes. En P1 elles echouent si offline ;
 *   la queue d'ecritures offline arrivera en P3.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val apiService: ApiService,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val completionDao: CompletionDao,
    private val userDao: UserDao,
) {

    // -------------------------------------------------------------------
    // Tasks — observe / refresh
    // -------------------------------------------------------------------

    /** Flow des taches actives (cache local Room, mis a jour par refresh). */
    fun observeActiveTasks(): Flow<List<Task>> =
        taskDao.observeActive().map { list -> list.map { it.toDomain() } }

    /** Flow d'une tache par id. */
    fun observeTaskById(id: String): Flow<Task?> =
        taskDao.observeById(id).map { it?.toDomain() }

    /**
     * Refresh la liste des taches depuis l'API et upsert dans Room.
     * Retourne true en cas de succes, false sinon (le Flow reste sur le cache).
     */
    suspend fun refreshTasks(): Boolean {
        return try {
            val response = apiService.getTasks(activeOnly = null)
            if (response.isSuccessful) {
                val tasks = response.body() ?: emptyList()
                taskDao.replaceAll(tasks.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------

    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun refreshCategories(): Boolean {
        return try {
            val response = apiService.getCategories()
            if (response.isSuccessful) {
                val categories = response.body() ?: emptyList()
                categoryDao.replaceAll(categories.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------

    fun observeUsers(): Flow<List<User>> =
        userDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun refreshUsers(): Boolean {
        return try {
            val response = apiService.getUsers()
            if (response.isSuccessful) {
                val users = response.body() ?: emptyList()
                userDao.replaceAll(users.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Completions
    // -------------------------------------------------------------------

    fun observeRecentCompletions(limit: Int = 50): Flow<List<Completion>> =
        completionDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeCompletionsByTask(taskId: String): Flow<List<Completion>> =
        completionDao.observeByTask(taskId).map { list -> list.map { it.toDomain() } }

    suspend fun refreshCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Boolean {
        return try {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (response.isSuccessful) {
                val completions = response.body() ?: emptyList()
                // Pour un fetch global (sans filtre tache), on remplace tout.
                // Pour un fetch par tache, on upsert sans purger les autres.
                if (taskId == null && userId == null) {
                    completionDao.replaceAll(completions.map { it.toEntity() })
                } else {
                    completionDao.upsertAll(completions.map { it.toEntity() })
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Ecritures (P1 : tape l'API direct, invalide le cache local apres succes)
    // En P3 ces methodes seront refactorees pour ecrire d'abord dans Room
    // (UI optimiste) et pousser dans la queue de sync.
    // -------------------------------------------------------------------

    suspend fun createTask(request: CreateTaskRequest): Result<Task> {
        return try {
            val response = apiService.createTask(request)
            if (response.isSuccessful) {
                val task = response.body()!!
                taskDao.upsert(task.toEntity())
                Result.success(task)
            } else {
                Result.failure(Exception("Erreur lors de la creation: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTask(taskId: String, request: UpdateTaskRequest): Result<Task> {
        return try {
            val response = apiService.updateTask(taskId, request)
            if (response.isSuccessful) {
                val task = response.body()!!
                taskDao.upsert(task.toEntity())
                Result.success(task)
            } else {
                Result.failure(Exception("Erreur lors de la mise a jour: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTask(taskId: String): Result<Unit> {
        return try {
            val response = apiService.deleteTask(taskId)
            if (response.isSuccessful) {
                taskDao.deleteById(taskId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Erreur lors de la suppression: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeTask(
        taskId: String,
        completedAt: String? = null,
        completedBy: String? = null,
        notes: String? = null,
    ): Result<Completion> {
        return try {
            val response = apiService.completeTask(
                taskId,
                CompleteTaskRequest(notes = notes, completedAt = completedAt, completedBy = completedBy),
            )
            if (response.isSuccessful) {
                val completion = response.body()!!
                completionDao.upsertAll(listOf(completion.toEntity()))
                Result.success(completion)
            } else {
                Result.failure(Exception("Erreur lors de la completion: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------
    // Methodes legacy "one-shot" — gardees pour rester compatibles avec
    // les ViewModels qui n'ont pas encore ete refactores. Elles font un
    // refresh + lisent depuis le cache. Seront supprimees apres migration
    // complete.
    // -------------------------------------------------------------------

    suspend fun getTasks(
        categoryId: String? = null,
        activeOnly: Boolean? = true,
    ): Result<List<Task>> {
        return try {
            val response = apiService.getTasks(categoryId, activeOnly)
            if (response.isSuccessful) {
                val tasks = response.body() ?: emptyList()
                taskDao.replaceAll(tasks.map { it.toEntity() })
                Result.success(tasks)
            } else {
                Result.failure(Exception("Erreur lors du chargement des taches: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = apiService.getCategories()
            if (response.isSuccessful) {
                val categories = response.body() ?: emptyList()
                categoryDao.replaceAll(categories.map { it.toEntity() })
                Result.success(categories)
            } else {
                Result.failure(Exception("Erreur lors du chargement des categories: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Result<List<Completion>> {
        return try {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (response.isSuccessful) {
                val completions = response.body() ?: emptyList()
                if (taskId == null && userId == null) {
                    completionDao.replaceAll(completions.map { it.toEntity() })
                } else {
                    completionDao.upsertAll(completions.map { it.toEntity() })
                }
                Result.success(completions)
            } else {
                Result.failure(Exception("Erreur lors du chargement des completions: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUsers(): Result<List<User>> {
        return try {
            val response = apiService.getUsers()
            if (response.isSuccessful) {
                val users = response.body() ?: emptyList()
                userDao.replaceAll(users.map { it.toEntity() })
                Result.success(users)
            } else {
                Result.failure(Exception("Erreur lors du chargement des utilisateurs: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

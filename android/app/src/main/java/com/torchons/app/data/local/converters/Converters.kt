package com.torchons.app.data.local.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.RecurrenceRule

/**
 * Convertisseurs Room <-> types complexes (listes, JSON, regle de recurrence).
 * Utilise Gson (deja present dans le projet pour Retrofit).
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun taskContextsToJson(value: List<TaskContextRef>?): String =
        gson.toJson(value ?: emptyList<TaskContextRef>())

    @TypeConverter
    fun jsonToTaskContexts(value: String?): List<TaskContextRef> {
        if (value.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<TaskContextRef>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun stringListToJson(value: List<String>?): String =
        gson.toJson(value ?: emptyList<String>())

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun recurrenceRuleToJson(rule: RecurrenceRule?): String? =
        rule?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToRecurrenceRule(value: String?): RecurrenceRule? {
        if (value.isNullOrEmpty()) return null
        return gson.fromJson(value, RecurrenceRule::class.java)
    }
}

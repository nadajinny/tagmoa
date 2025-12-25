package com.ndjinny.tagmoa.model

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseException

fun DataSnapshot.toSubTaskSafe(parentTaskId: String? = null): SubTask? {
    val raw = value
    if (raw !is Map<*, *>) {
        return try {
            getValue(SubTask::class.java)
        } catch (_: DatabaseException) {
            null
        }
    }

    val id = (raw["id"] as? String).orEmpty().ifBlank { key.orEmpty() }
    val mainTaskId = (raw["mainTaskId"] as? String).orEmpty().ifBlank { parentTaskId.orEmpty() }

    return SubTask(
        id = id,
        mainTaskId = mainTaskId,
        content = (raw["content"] as? String).orEmpty(),
        priority = raw.readInt("priority", 0),
        startDate = raw.readLong("startDate"),
        endDate = raw.readLong("endDate"),
        dueDate = raw.readLong("dueDate"),
        isCompleted = raw.readBoolean("isCompleted", "completed"),
        completedAt = raw.readLong("completedAt"),
        alarmEnabled = raw.readBoolean("alarmEnabled"),
        alarmLeadMinutes = raw.readInt("alarmLeadMinutes", 0),
        alarmTimeMillis = raw.readLong("alarmTimeMillis")
    )
}

private fun Map<*, *>.readInt(key: String, defaultValue: Int): Int {
    val value = this[key]
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        is Boolean -> if (value) 1 else 0
        else -> defaultValue
    }
}

private fun Map<*, *>.readLong(key: String): Long? {
    val value = this[key]
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is Boolean -> if (value) 1L else 0L
        else -> null
    }
}

private fun Map<*, *>.readBoolean(vararg keys: String): Boolean {
    for (key in keys) {
        val value = this[key] ?: continue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }
    return false
}

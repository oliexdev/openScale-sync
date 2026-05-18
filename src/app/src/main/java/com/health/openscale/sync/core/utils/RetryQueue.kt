package com.health.openscale.sync.core.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import java.util.Date

class RetryQueue(context: Context, serviceKey: String) {

    data class PendingOp(
        val type: String,
        val id: Int = 0,
        val userId: Int = 0,
        val dateMs: Long = 0L,
        val weight: Float = 0f,
        val fat: Float = 0f,
        val water: Float = 0f,
        val muscle: Float = 0f,
        val extraFields: Map<String, Float> = emptyMap()
    ) {
        fun toMeasurement() = OpenScaleMeasurement(id, userId, Date(dateMs), weight, fat, water, muscle, extraFields)
    }

    companion object {
        private const val MAX_SIZE = 500

        fun insert(m: OpenScaleMeasurement) =
            PendingOp("insert", m.id, m.userId, m.date.time, m.weight, m.fat, m.water, m.muscle, m.extraFields)

        fun update(m: OpenScaleMeasurement) =
            PendingOp("update", m.id, m.userId, m.date.time, m.weight, m.fat, m.water, m.muscle, m.extraFields)

        fun delete(date: Date) = PendingOp("delete", dateMs = date.time)

        fun clear() = PendingOp("clear")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("retry_queue_$serviceKey", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<PendingOp>>() {}.type

    @Synchronized
    fun enqueue(op: PendingOp) {
        val current = if (op.type == "clear") mutableListOf() else peek().toMutableList()
        current.add(op)
        if (current.size > MAX_SIZE) current.subList(0, current.size - MAX_SIZE).clear()
        prefs.edit().putString("queue", gson.toJson(current)).apply()
    }

    @Synchronized
    fun peek(): List<PendingOp> {
        val json = prefs.getString("queue", null) ?: return emptyList()
        return runCatching { gson.fromJson<List<PendingOp>>(json, listType) ?: emptyList() }
            .getOrElse { emptyList() }
    }

    @Synchronized
    fun replace(ops: List<PendingOp>) {
        prefs.edit().putString("queue", gson.toJson(ops)).apply()
    }

    fun isEmpty(): Boolean = peek().isEmpty()
    fun size(): Int = peek().size
}

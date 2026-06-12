/*
 *  Copyright (C) 2025  olie.xdev <olie.xdev@googlemail.com>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package com.health.openscale.sync.core.datatypes

import androidx.annotation.Keep
import org.json.JSONArray
import java.util.Date

/**
 * One generic measurement value (Phase 2). Self-describing so openScale-sync needs none of
 * openScale's enums: [key] is the MeasurementTypeKey enum name ("WEIGHT"/"WAIST"/"CUSTOM"…),
 * [unit] is a UCUM code ("kg"/"%"/"cm"/"kcal"/"/min"/"Ohm"/""), and [value] is already in the
 * canonical base unit of its dimension. Custom types share key=="CUSTOM" and are distinguished by
 * [typeId] → stable backend key "custom_<typeId>".
 */
@Keep
data class OpenScaleMeasurementValue(
    val typeId: Int,
    val key: String,
    val name: String,
    val unit: String,
    val isDerived: Boolean,
    val value: Float? = null,
    val text: String? = null
) {
    /** Stable backend field/key: predefined types use the enum name (lower-case), custom use the id. */
    fun backendKey(): String =
        if (key == "CUSTOM") "custom_$typeId" else key.lowercase()

    companion object {
        /** Parses openScale's generic, self-describing value JSON (the "values"/"values_json" payload). */
        fun parseList(json: String?): List<OpenScaleMeasurementValue> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    OpenScaleMeasurementValue(
                        typeId = o.optInt("typeId", 0),
                        key = o.optString("key", ""),
                        name = o.optString("name", ""),
                        unit = o.optString("unit", ""),
                        isDerived = o.optBoolean("isDerived", false),
                        value = if (o.has("value")) o.getDouble("value").toFloat() else null,
                        text = if (o.has("text")) o.optString("text") else null
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}

@Keep
data class OpenScaleMeasurement(
    val id: Int,
    val userId: Int,
    val date: Date,
    val weight: Float,
    val fat: Float,
    val water: Float,
    val muscle: Float,
    // Human-readable openScale user name; carried for multi-user routing/labelling
    // (MQTT topic uses the stable userId, this is for the JSON payload / HA display name).
    val username: String = "",
    // Generic, self-describing value set (all types incl. custom) — the single source of truth.
    // weight/fat/water/muscle above are convenience fields derived from it (see [fromValues]).
    val values: List<OpenScaleMeasurementValue> = emptyList()
) {
    companion object {
        /**
         * Builds a measurement from the generic value set, deriving the convenience weight/fat/
         * water/muscle fields: weight in kg (WEIGHT, canonical); fat/water/muscle as % (taken
         * directly when the value's unit is "%", otherwise converted from kg using the weight).
         */
        fun fromValues(
            id: Int, userId: Int, date: Date, username: String,
            values: List<OpenScaleMeasurementValue>
        ): OpenScaleMeasurement {
            val weight = values.firstOrNull { it.key == "WEIGHT" }?.value ?: 0f
            fun pct(key: String): Float {
                val v = values.firstOrNull { it.key == key } ?: return 0f
                val value = v.value ?: return 0f
                return if (v.unit == "%") value else if (weight > 0f) value / weight * 100f else 0f
            }
            return OpenScaleMeasurement(
                id, userId, date, weight, pct("BODY_FAT"), pct("WATER"), pct("MUSCLE"), username, values
            )
        }
    }
}
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
 * One generic measurement value. Self-describing so openScale-sync needs none of
 * openScale's internals.
 *
 * The type identifier is [identity] — a namespaced string openScale keeps stable across
 * installations and renames: `builtin.weight` (predefined), `ble.segmental.fat.left_arm`
 * (contributed by a scale), `user.schritte` (user-created). It is the only identifier
 * since API v3; entries without one are dropped by [parseList]. [unit] is a UCUM code
 * ("kg"/"%"/"cm"/"kcal"/"/min"/"Ohm"/""), and [value] is already in the canonical base
 * unit of its dimension.
 */
@Keep
data class OpenScaleMeasurementValue(
    val identity: String,
    val name: String,
    val unit: String,
    val isDerived: Boolean,
    val value: Float? = null,
    val text: String? = null
) {
    /**
     * Stable backend field/key (InfluxDB fields, MQTT history columns, webhook keys):
     * strip the namespace, dots to underscores, lower-case — `builtin.weight` → `weight`,
     * `ble.ecw` → `ecw`, `user.schritte` → `schritte`. One rule for every type, stable
     * across installations; openScale guarantees the derived names cannot collide.
     */
    fun backendKey(): String = identity.substringAfter('.').replace('.', '_').lowercase()

    /** True when this value is the given predefined quantity, e.g. isBuiltin("BODY_FAT"). */
    fun isBuiltin(builtinKey: String): Boolean = identity == "builtin." + builtinKey.lowercase()

    companion object {
        /** Parses openScale's generic, self-describing value JSON (the "values"/"values_json"
         *  payload). Entries without an `identity` (pre-v3 peers) are skipped — the
         *  MIN_API_VERSION gate makes such payloads a misconfiguration, not a supported input. */
        fun parseList(json: String?): List<OpenScaleMeasurementValue> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val identity = o.optString("identity", "")
                    if (identity.isBlank()) return@mapNotNull null
                    OpenScaleMeasurementValue(
                        identity = identity,
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
    val body_fat: Float,
    val water: Float,
    val muscle: Float,
    val lbm: Float,
    val bone: Float,
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
            val weight = values.firstOrNull { it.isBuiltin("WEIGHT") }?.value ?: 0f
            fun pct(key: String): Float {
                val v = values.firstOrNull { it.isBuiltin(key) } ?: return 0f
                val value = v.value ?: return 0f
                return if (v.unit == "%") value else if (weight > 0f) value / weight * 100f else 0f
            }
            return OpenScaleMeasurement(
                id, userId, date, weight, pct("BODY_FAT"), pct("WATER"), pct("MUSCLE"), pct("LBM"), pct("BONE"),username, values
            )
        }

        /** A canonical convenience metric: its openScale generic-value [key], UCUM [unit] and an
         *  [accessor] for the derived convenience field. Its wire/field name is [backendKey] (the key
         *  lower-cased), identical to [OpenScaleMeasurementValue.backendKey] and used uniformly by all
         *  backends. Single source of truth — backends derive their columns/fields from this list. */
        data class CanonicalMetric(
            val key: String,
            val unit: String,
            val accessor: (OpenScaleMeasurement) -> Float
        ) {
            val backendKey: String get() = key.lowercase()
        }

        val CANONICAL_METRICS = listOf(
            CanonicalMetric("WEIGHT", "kg") { it.weight },
            CanonicalMetric("BODY_FAT", "%") { it.body_fat },
            CanonicalMetric("WATER", "%") { it.water },
            CanonicalMetric("MUSCLE", "%") { it.muscle },
        )
    }
}
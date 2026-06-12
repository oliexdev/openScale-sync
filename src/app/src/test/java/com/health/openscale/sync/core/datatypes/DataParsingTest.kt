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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Robustness of the generic-value parsing/derivation that turns an openScale "values" payload (which
 * arrives over an Intent and may be empty, blank, or outright malformed) into an [OpenScaleMeasurement]
 * — it must never throw and must degrade to sensible defaults. Uses Robolectric for android's org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DataParsingTest {

    // --- parseList: hostile / empty input never throws -----------------------------------

    @Test fun parseList_null_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList(null).isEmpty())
    @Test fun parseList_blank_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("   ").isEmpty())
    @Test fun parseList_garbage_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("not json at all").isEmpty())
    @Test fun parseList_emptyArray_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("[]").isEmpty())
    @Test fun parseList_objectNotArray_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("{\"a\":1}").isEmpty())
    @Test fun parseList_truncatedJson_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("[{\"key\":\"WEIGHT\"").isEmpty())

    @Test
    fun parseList_validValue_mapsAllFields() {
        val v = OpenScaleMeasurementValue.parseList(
            """[{"typeId":0,"key":"WEIGHT","name":"Weight","unit":"kg","isDerived":false,"value":80.5}]"""
        ).single()
        assertEquals("WEIGHT", v.key)
        assertEquals("kg", v.unit)
        assertEquals(80.5f, v.value!!, 0.0001f)
        assertEquals("weight", v.backendKey())
    }

    @Test
    fun parseList_customType_usesStableBackendKey() {
        val v = OpenScaleMeasurementValue.parseList(
            """[{"typeId":8,"key":"CUSTOM","name":"Visceral","unit":"","isDerived":false,"value":7.0}]"""
        ).single()
        assertEquals("custom_8", v.backendKey())
    }

    @Test
    fun parseList_textOnlyValue_hasNoNumber() {
        val v = OpenScaleMeasurementValue.parseList(
            """[{"typeId":3,"key":"COMMENT","name":"Note","unit":"","isDerived":false,"text":"hi"}]"""
        ).single()
        assertNull(v.value)
        assertEquals("hi", v.text)
    }

    @Test
    fun parseList_missingFields_useDefaults() {
        // only "key" present — everything else falls back, no crash
        val v = OpenScaleMeasurementValue.parseList("""[{"key":"WAIST"}]""").single()
        assertEquals("WAIST", v.key)
        assertEquals("", v.unit)
        assertNull(v.value)
        assertEquals("waist", v.backendKey())
    }

    // --- fromValues: weight is derived; guards against missing weight / divide-by-zero ----

    @Test
    fun fromValues_emptyValues_yieldsZeroWeight() {
        val m = OpenScaleMeasurement.fromValues(1, 1, Date(0), "", emptyList())
        assertEquals(0f, m.weight, 0f)
        assertEquals(0f, m.fat, 0f)
    }

    @Test
    fun fromValues_percentUnit_takenDirectly() {
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(
                OpenScaleMeasurementValue(0, "WEIGHT", "Weight", "kg", false, 80f),
                OpenScaleMeasurementValue(0, "BODY_FAT", "Fat", "%", false, 25f),
            )
        )
        assertEquals(80f, m.weight, 0f)
        assertEquals(25f, m.fat, 0.0001f)
    }

    @Test
    fun fromValues_absoluteUnit_convertedToPercentViaWeight() {
        // fat stored in kg (8 kg of 80 kg → 10 %)
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(
                OpenScaleMeasurementValue(0, "WEIGHT", "Weight", "kg", false, 80f),
                OpenScaleMeasurementValue(0, "BODY_FAT", "Fat", "kg", false, 8f),
            )
        )
        assertEquals(10f, m.fat, 0.0001f)
    }

    @Test
    fun fromValues_absoluteUnit_withoutWeight_doesNotDivideByZero() {
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(OpenScaleMeasurementValue(0, "BODY_FAT", "Fat", "kg", false, 8f))
        )
        assertEquals(0f, m.weight, 0f)
        assertEquals(0f, m.fat, 0f)   // weight 0 → guarded, no NaN/Infinity
    }
}

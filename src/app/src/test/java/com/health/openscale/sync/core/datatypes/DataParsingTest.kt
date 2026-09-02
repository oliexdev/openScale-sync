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
    @Test fun parseList_truncatedJson_isEmpty() = assertTrue(OpenScaleMeasurementValue.parseList("[{\"identity\":\"builtin.weight\"").isEmpty())

    @Test
    fun parseList_validValue_mapsAllFields() {
        val v = OpenScaleMeasurementValue.parseList(
            """[{"identity":"builtin.weight","name":"Weight","unit":"kg","isDerived":false,"value":80.5}]"""
        ).single()
        assertEquals("builtin.weight", v.identity)
        assertEquals("kg", v.unit)
        assertEquals(80.5f, v.value!!, 0.0001f)
        assertEquals("weight", v.backendKey())
    }

    @Test
    fun parseList_textOnlyValue_hasNoNumber() {
        val v = OpenScaleMeasurementValue.parseList(
            """[{"identity":"builtin.comment","name":"Note","unit":"","isDerived":false,"text":"hi"}]"""
        ).single()
        assertNull(v.value)
        assertEquals("hi", v.text)
    }

    @Test
    fun parseList_missingFields_useDefaults() {
        // only "identity" present — everything else falls back, no crash
        val v = OpenScaleMeasurementValue.parseList("""[{"identity":"builtin.waist"}]""").single()
        assertEquals("", v.unit)
        assertNull(v.value)
        assertEquals("waist", v.backendKey())
    }

    // --- fromValues: weight is derived; guards against missing weight / divide-by-zero ----

    @Test
    fun fromValues_emptyValues_yieldsZeroWeight() {
        val m = OpenScaleMeasurement.fromValues(1, 1, Date(0), "", emptyList())
        assertEquals(0f, m.weight, 0f)
        assertEquals(0f, m.body_fat, 0f)
    }

    @Test
    fun fromValues_percentUnit_takenDirectly() {
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(
                OpenScaleMeasurementValue("builtin.weight", "Weight", "kg", false, 80f),
                OpenScaleMeasurementValue("builtin.body_fat", "Fat", "%", false, 25f),
            )
        )
        assertEquals(80f, m.weight, 0f)
        assertEquals(25f, m.body_fat, 0.0001f)
    }

    @Test
    fun fromValues_absoluteUnit_convertedToPercentViaWeight() {
        // fat stored in kg (8 kg of 80 kg → 10 %)
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(
                OpenScaleMeasurementValue("builtin.weight", "Weight", "kg", false, 80f),
                OpenScaleMeasurementValue("builtin.body_fat", "Fat", "kg", false, 8f),
            )
        )
        assertEquals(10f, m.body_fat, 0.0001f)
    }

    @Test
    fun fromValues_absoluteUnit_withoutWeight_doesNotDivideByZero() {
        val m = OpenScaleMeasurement.fromValues(
            1, 1, Date(0), "",
            listOf(OpenScaleMeasurementValue("builtin.body_fat", "Fat", "kg", false, 8f))
        )
        assertEquals(0f, m.weight, 0f)
        assertEquals(0f, m.body_fat, 0f)   // weight 0 → guarded, no NaN/Infinity
    }

    // --- identity (openScale API v3) -------------------------------------------

    @Test
    fun `identity is parsed and drives the backend key`() {
        val json = """[
            {"identity":"builtin.weight","unit":"kg","value":72.5},
            {"identity":"ble.ecw","name":"Extracellular water","unit":"%","value":24.6},
            {"identity":"user.schritte","name":"Schritte","unit":"","value":8000.0}
        ]"""

        val parsed = OpenScaleMeasurementValue.parseList(json)

        // One rule for every type: strip the namespace, lower-case. builtin.weight keeps the
        // field name it always had; ble.ecw gets back the "ecw" it had before leaving the
        // predefined set; user.schritte is stable across installations, unlike custom_42.
        assertEquals(listOf("weight", "ecw", "schritte"), parsed.map { it.backendKey() })
    }

    @Test
    fun `entries without identity are dropped - pre-v3 payloads are not a supported input`() {
        val json = """[
            {"typeId":1,"key":"WEIGHT","unit":"kg","value":72.5},
            {"identity":"builtin.waist","unit":"cm","value":80.0}
        ]"""

        val parsed = OpenScaleMeasurementValue.parseList(json)

        assertEquals(listOf("waist"), parsed.map { it.backendKey() })
    }

    @Test
    fun `unknown extra fields like inputType are ignored gracefully`() {
        // A realistic payload from a current openScale, including fields this app never reads.
        val json = """[{"identity":"builtin.body_fat",""" +
            """"name":"BODY_FAT","unit":"%","inputType":"FLOAT","isDerived":false,"value":21.3}]"""

        val parsed = OpenScaleMeasurementValue.parseList(json)

        assertEquals("body_fat", parsed.single().backendKey())
        assertEquals(21.3f, parsed.single().value!!, 1e-4f)
    }
}

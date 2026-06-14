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
package com.health.openscale.sync.core.model

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the per-user "last published date" tracking in [MQTTViewModel] — the state that gates the
 * retained ".../<userId>/measurements/last" topic each Home Assistant device subscribes to. Before
 * the multi-user fix this was a single global Long, so one user's newest measurement suppressed
 * every other user's. SDK 34 sidesteps Robolectric's lag behind compileSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MQTTViewModelTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = ctx.getSharedPreferences("mqtt_vm_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun unknownUserDefaultsToZero() {
        val vm = MQTTViewModel(prefs)
        assertEquals(0L, vm.getLastPublishedDate(1))
    }

    @Test
    fun datesAreTrackedIndependentlyPerUser() {
        val vm = MQTTViewModel(prefs)
        vm.setLastPublishedDate(1, 1000L)
        vm.setLastPublishedDate(2, 500L)

        // Bob (user 2) keeps his older date even though Alice (user 1) has a newer one — the regression.
        assertEquals(1000L, vm.getLastPublishedDate(1))
        assertEquals(500L, vm.getLastPublishedDate(2))
    }

    @Test
    fun clearRemovesOnlyTheGivenUser() {
        val vm = MQTTViewModel(prefs)
        vm.setLastPublishedDate(1, 1000L)
        vm.setLastPublishedDate(2, 500L)

        vm.clearLastPublishedDate(1)

        assertEquals(0L, vm.getLastPublishedDate(1))
        assertEquals(500L, vm.getLastPublishedDate(2))
    }

    @Test
    fun datesSurviveReinstantiation() {
        MQTTViewModel(prefs).apply {
            setLastPublishedDate(1, 1000L)
            setLastPublishedDate(2, 500L)
        }

        // A fresh instance (e.g. after process death) must reload the per-user map from prefs.
        val reloaded = MQTTViewModel(prefs)
        assertEquals(1000L, reloaded.getLastPublishedDate(1))
        assertEquals(500L, reloaded.getLastPublishedDate(2))
    }
}

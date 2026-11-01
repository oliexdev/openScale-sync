package com.health.openscale.sync.core.provider

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenScaleDataProviderTest {
    private lateinit var data: OpenScaleDataProvider

    class FailingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("insert unavailable")
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int = error("update unavailable")
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val provider = FailingProvider()
        val authority = "com.health.openscale.provider"
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        data = OpenScaleDataProvider(context, context.getSharedPreferences("provider-test", Context.MODE_PRIVATE))
    }

    @Test
    fun insertExceptionReachesSyncPipeline() {
        assertThrows(IllegalStateException::class.java) {
            data.insertMeasurement(1, 1000L, 80f, throwOnFailure = true)
        }
    }

    @Test
    fun updateExceptionIsNotReportedAsAnUnchangedRow() {
        assertThrows(IllegalStateException::class.java) {
            data.updateMeasurement(1, 1000L, 80f, throwOnFailure = true)
        }
    }

    @Test
    fun ordinaryImportsKeepTheirExistingFailureResults() {
        assertFalse(data.insertMeasurement(1, 1000L, 80f))
        assertFalse(data.updateMeasurement(1, 1000L, 80f))
    }
}

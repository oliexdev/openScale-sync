/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;

import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.core.datatypes.ScaleUser;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import timber.log.Timber;

public class OpenScaleProvider {
    private Context context;

    private SharedPreferences prefs;

    private Uri metaUri;
    private Uri usersUri;
    private Uri measurementsUri;

    public OpenScaleProvider(Context context) {
        this.context = context;

        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String packageName = prefs.getString("openScalePackageName", "com.health.openscale.pro");

        metaUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(packageName + ".provider")
                .path("meta")
                .build();

        usersUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(packageName + ".provider")
                .path("users")
                .build();

        measurementsUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(packageName + ".provider")
                .path("measurements")
                .build();
    }

    public boolean checkVersion() {
        try {
            Cursor cursor = context.getContentResolver().query(
                    metaUri, null, null, null, null);

            try {
                while (cursor.moveToNext()) {
                    int apiVersion = cursor.getInt(cursor.getColumnIndex("apiVersion"));
                    int versionCode = cursor.getInt(cursor.getColumnIndex("versionCode"));

                    Timber.d("openScale version " + versionCode + " with content provider API version " + apiVersion);

                    if (versionCode >= 43) {
                        return true;
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            Timber.e(context.getResources().getString(R.string.txt_openScale_provider_error) + " " + e.getMessage());
        }

        return false;
    }

    public List<ScaleUser> getUsers() {
        ArrayList<ScaleUser> openScaleUsers = new ArrayList<>();

        try {
            Cursor cursor = context.getContentResolver().query(
                    usersUri, null, null, null, null);

            try {
                while (cursor.moveToNext()) {
                    long userId = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
                    String name = cursor.getString(cursor.getColumnIndex("username"));

                    openScaleUsers.add(new ScaleUser((int)userId, name));
                }
            } finally {
                cursor.close();
            }
        }
        catch (Exception e) {
            Timber.e(context.getResources().getString(R.string.txt_openScale_provider_error) + " " + e.getMessage());
        }

        return openScaleUsers;
    }

    public List<ScaleMeasurement> getMeasurements(int userId) {
        ArrayList<ScaleMeasurement> openScaleMeasurements = new ArrayList<>();

        try {
            Cursor cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(measurementsUri, userId),
                    null, null, null, null);

            try {
                while (cursor.moveToNext()) {
                    long datetime = cursor.getLong(cursor.getColumnIndex("datetime"));
                    float weight = cursor.getFloat(cursor.getColumnIndex("weight"));

                    openScaleMeasurements.add(new ScaleMeasurement(new Date(datetime), weight));
                }
            } finally {
                cursor.close();
            }
        }
        catch (Exception e) {
            Timber.e(context.getResources().getString(R.string.txt_openScale_provider_error) + " " + e.getMessage());
        }

        return openScaleMeasurements;
    }

    public void insertMeasurement(Date date, float weight, int userId) {
        try {
            ContentValues values = new ContentValues();

            values.put("datetime", date.getTime());
            values.put("weight", weight);
            values.put("userId", userId);

            context.getContentResolver().insert(measurementsUri, values);
        } catch (Exception e) {
            Timber.e(context.getResources().getString(R.string.txt_openScale_provider_error) + " " + e.getMessage());
        }
    }
}

/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import timber.log.Timber;

public class OpenScaleProvider {
    private Context context;
    private Uri metaUri;
    private Uri usersUri;
    private Uri measurementsUri;

    public OpenScaleProvider(Context context, String packageName) {
        this.context = context;

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



    public class OpenScaleUser {
        public OpenScaleUser(int userid, String name) {
            this.userid = userid;
            this.name = name;
        }

        @Override
        public String toString() {
            return "userId " + userid + " name " + name;
        }

        public int userid;
        public String name;
    }

    public class OpenScaleMeasurement {
        public OpenScaleMeasurement(Date date, float weight) {
            this.date = date;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return "date " + date + " weight " + weight;
        }

        public Date date;
        public float weight;
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
            Timber.e("openScale content provider error: " + e.getMessage());
        }

        return false;
    }

    public List<OpenScaleUser> getUsers() {
        ArrayList<OpenScaleUser> openScaleUsers = new ArrayList<>();

        try {
            Cursor cursor = context.getContentResolver().query(
                    usersUri, null, null, null, null);

            try {
                while (cursor.moveToNext()) {
                    long userId = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
                    String name = cursor.getString(cursor.getColumnIndex("username"));

                    openScaleUsers.add(new OpenScaleUser((int)userId, name));
                }
            } finally {
                cursor.close();
            }
        }
        catch (Exception e) {
            Timber.e("openScale content provider error: " + e.getMessage());
        }

        return openScaleUsers;
    }

    public List<OpenScaleMeasurement> getMeasurements(int userId) {
        ArrayList<OpenScaleMeasurement> openScaleMeasurements = new ArrayList<>();

        try {
            Cursor cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(measurementsUri, userId),
                    null, null, null, null);

            try {
                while (cursor.moveToNext()) {
                    long datetime = cursor.getLong(cursor.getColumnIndex("datetime"));
                    float weight = cursor.getFloat(cursor.getColumnIndex("weight"));

                    openScaleMeasurements.add(new OpenScaleMeasurement(new Date(datetime), weight));
                }
            } finally {
                cursor.close();
            }
        }
        catch (Exception e) {
            Timber.e("openScale content provider error: " + e.getMessage());
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
            Timber.e("openScale content provider error: " + e.getMessage());
        }
    }
}

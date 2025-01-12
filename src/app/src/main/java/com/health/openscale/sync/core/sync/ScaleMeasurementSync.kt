/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.Date;

public abstract class ScaleMeasurementSync {
    protected SharedPreferences prefs;

    public ScaleMeasurementSync(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public abstract String getName();
    public abstract boolean isEnable();
    public abstract void insert(final ScaleMeasurement measurement);
    public abstract void delete(final Date date);
    public abstract void clear();
    public abstract void update(final ScaleMeasurement measurement);
    public abstract void checkStatus(final StatusView statusView);
}

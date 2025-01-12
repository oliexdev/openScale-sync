/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import static android.os.Looper.getMainLooper;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.Date;

import timber.log.Timber;

public class HealthConnectSync extends ScaleMeasurementSync {
    private Context context;

    public HealthConnectSync(Context context) {
        super(context);
        this.context = context;
    }


    @Override
    public String getName() {
        return "HealthConnectSync";
    }

    @Override
    public boolean isEnable() {
        return prefs.getBoolean("enableHealthConnect", true);
    }

    private void showToast(final String msg) {
        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean checkPermission() {


        return true;
    }

    @Override
    public void insert(final ScaleMeasurement measurement) {
        if (!checkPermission()) {
            return;
        }

    }

    @Override
    public void delete(final Date date) {
        if (!checkPermission()) {
            return;
        }

    }

    @Override
    public void clear() {
        if (!checkPermission()) {
            return;
        }

    }

    @Override
    public void update(final ScaleMeasurement measurement) {
        if (!checkPermission()) {
            return;
        }

    }

    @Override
    public void checkStatus(final StatusView statusView) {
        Timber.d("Check Health Connect sync status");

        if (!isEnable()) {
            statusView.setCheck(false, "Health Connect sync is disabled");
            return;
        }
    }
}

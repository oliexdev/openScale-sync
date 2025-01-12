/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleUser;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;
import com.health.openscale.sync.core.sync.HealthConnectSync;
import com.health.openscale.sync.core.sync.MQTTSync;
import com.health.openscale.sync.core.sync.WgerSync;
import com.health.openscale.sync.gui.utils.DebugTree;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class OverviewFragment extends Fragment {
    private final int OPENSCALE_PERMISSIONS_REQUEST_CODE = 101;
    private final int DEBUG_WRITE_PERMISSIONS_REQUEST_CODE = 102;

    private SharedPreferences prefs;

    private LinearLayout overviewMainLayout;
    static private DebugTree debugTree = new DebugTree();
    private Switch chkDebugLog;
    private StatusView statusViewGoogleFitSync;
    private StatusView statusViewHealthConnectSync;
    private StatusView statusViewMQTTSync;
    private StatusView statusViewWgerSync;
    private StatusView statusViewOpenScale;
    private StatusView statusOpenScaleUser;
    private Button btnInstallOpenScale;
    private Button btnPermissionOpenScale;
    private Spinner spinScaleUer;
    private ArrayAdapter<String> spinScaleUserAdapter;

    private GoogleFitSync googleFitSync;
    private HealthConnectSync healthConnectSync;
    private MQTTSync mqttSync;
    private WgerSync wgerSync;

    public OverviewFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_overview, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        googleFitSync = new GoogleFitSync(getContext());
        healthConnectSync = new HealthConnectSync(getContext());
        mqttSync = new MQTTSync(getContext());
        wgerSync = new WgerSync(getContext());

        chkDebugLog = fragment.findViewById(R.id.chkDebugLog);

        chkDebugLog.setChecked(debugTree.isLogging());
        chkDebugLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    debugTree.close();
                } else {
                    startActivityForResult(debugTree.requestDebugIntent(), DEBUG_WRITE_PERMISSIONS_REQUEST_CODE);
                }
            }
        });

        String openScalePackageName = "com.health.openscale.pro";

        if (!isPackageInstalled(openScalePackageName)) {
            openScalePackageName = "com.health.openscale";

            if (!isPackageInstalled(openScalePackageName)) {
                openScalePackageName = "com.health.openscale.light";

                if (!isPackageInstalled(openScalePackageName)) {
                    Timber.d("no openScale version found on startup");
                }
            }
        }

        prefs.edit().putString("openScalePackageName", openScalePackageName).commit();

        overviewMainLayout = fragment.findViewById(R.id.overviewMainLayout);

        statusViewOpenScale = new StatusView(getContext(), getResources().getString(R.string.txt_openScale_status));
        btnInstallOpenScale = statusViewOpenScale.addButton(getResources().getString(R.string.txt_install_openScale));
        btnPermissionOpenScale = statusViewOpenScale.addButton(getResources().getString(R.string.txt_request_openScale_permission));

        statusOpenScaleUser = new StatusView(getContext(),getResources().getString(R.string.txt_openScale_user));
        spinScaleUer = statusOpenScaleUser.addSpinner();

        statusViewGoogleFitSync = new StatusView(getContext(), getResources().getString(R.string.txt_googlefit_status));
        statusViewHealthConnectSync = new StatusView(getContext(), getResources().getString(R.string.txt_healthConnect_status));
        statusViewMQTTSync = new StatusView(getContext(), getResources().getString(R.string.txt_mqtt_status));
        statusViewWgerSync = new StatusView(getContext(), getResources().getString(R.string.txt_wger_status));

        overviewMainLayout.addView(statusViewGoogleFitSync);
        overviewMainLayout.addView(statusViewHealthConnectSync);
        overviewMainLayout.addView(statusViewMQTTSync);
        overviewMainLayout.addView(statusViewWgerSync);
        overviewMainLayout.addView(statusViewOpenScale);
        overviewMainLayout.addView(statusOpenScaleUser);

        spinScaleUserAdapter = new ArrayAdapter<>(getContext(), R.layout.spinner_item, new ArrayList<String>());
        spinScaleUer.setAdapter(spinScaleUserAdapter);

        spinScaleUer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (checkOpenScaleConnection()) {
                    OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());

                    List<ScaleUser> openScaleUsers = openScaleProvider.getUsers();

                    if (!openScaleUsers.isEmpty()) {
                        int openScaleUserId = openScaleUsers.get(position).userid;

                        prefs.edit().putInt("openScaleUserId", openScaleUserId).commit();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        btnInstallOpenScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d(getResources().getString(R.string.txt_install_button_clicked));

                Intent goToMarket = new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse("market://details?id=com.health.openscale.pro"));
                startActivity(goToMarket);
            }
        });

        btnPermissionOpenScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d(getResources().getString(R.string.txt_permission_button_clicked));

                requestOpenScalePermission();
            }
        });

        checkStatus();

        return fragment;
    }

    private void checkStatus() {
        if (checkOpenScaleConnection()) {
            checkOpenScaleUsers();
        }

        googleFitSync.checkStatus(statusViewGoogleFitSync);
        healthConnectSync.checkStatus(statusViewHealthConnectSync);
        mqttSync.checkStatus(statusViewMQTTSync);
        wgerSync.checkStatus(statusViewWgerSync);
    }

    private boolean checkOpenScaleConnection() {
        String packageName = prefs.getString("openScalePackageName", "com.health.openscale.pro");

        if (isPackageInstalled(packageName)) {
            if (ActivityCompat.checkSelfPermission(getContext(), packageName + ".READ_WRITE_DATA") == PackageManager.PERMISSION_GRANTED) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());

                if (openScaleProvider.checkVersion()) {
                    statusViewOpenScale.setCheck(true, getResources().getString(R.string.txt_openScale_version_is_ok) + "(" + packageName + ")");
                    return true;
                } else {
                    statusViewOpenScale.setCheck(false, getResources().getString(R.string.txt_openScale_version_too_old));
                }
            } else {
                statusViewOpenScale.setCheck(false, getResources().getString(R.string.txt_openScale_permission_not_granted));
            }
        } else {
            statusViewOpenScale.setCheck(false, getResources().getString(R.string.txt_openScale_is_not_installed));
        }

        return false;
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getContext().getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean checkOpenScaleUsers() {
        OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());

        if (openScaleProvider.getUsers().isEmpty()) {
            statusOpenScaleUser.setCheck(false, getResources().getString(R.string.txt_openScale_user_not_found));
        } else {
            int scaleUserId = prefs.getInt("openScaleUserId", 0);

            spinScaleUserAdapter.clear();

            int spinPos = 0;

            for (ScaleUser openScaleUser : openScaleProvider.getUsers()) {
                spinScaleUserAdapter.add(openScaleUser.name);

                if (openScaleUser.userid == scaleUserId) {
                    spinPos = spinScaleUserAdapter.getCount() - 1;
                }
            }

            spinScaleUer.setSelection(spinPos);
            statusOpenScaleUser.setCheck(true, getResources().getString(R.string.txt_openScale_user_found) + "(" + spinScaleUer.getSelectedItem() + ")");
        }

        return true;
    }

    private void requestOpenScalePermission() {
        String packageName = prefs.getString("openScalePackageName", "com.health.openscale.pro");

        requestPermissions(
                new String[]{packageName + ".READ_WRITE_DATA"},
                OPENSCALE_PERMISSIONS_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DEBUG_WRITE_PERMISSIONS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            debugTree.startLogTo(getContext(), data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == OPENSCALE_PERMISSIONS_REQUEST_CODE) {
            if(grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusViewOpenScale.setCheck(true, getResources().getString(R.string.txt_openScale_connection_successful));
                checkOpenScaleUsers();
            } else {
                statusViewOpenScale.setCheck(false, getResources().getString(R.string.txt_openScale_connection_failed));
            }
        }
    }
}

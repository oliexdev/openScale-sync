/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import com.health.openscale.sync.gui.utils.DebugTree;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

import static android.app.Activity.RESULT_OK;

public class OverviewFragment extends Fragment {
    private final int OPENSCALE_PERMISSIONS_REQUEST_CODE = 101;
    private final int DEBUG_WRITE_PERMISSIONS_REQUEST_CODE = 102;

    private SharedPreferences prefs;

    private LinearLayout overviewMainLayout;
    private DebugTree debugTree;
    private Switch chkDebugLog;
    private StatusView statusGoogleFitSync;
    private StatusView statusMQTTSync;
    private StatusView statusOpenScaleConnection;
    private StatusView statusOpenScaleUser;
    private Button btnInstallOpenScale;
    private Button btnPermissionOpenScale;
    private Spinner spinScaleUer;
    private ArrayAdapter<String> spinScaleUserAdapter;

    public OverviewFragment() {
        debugTree = new DebugTree();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_overview, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        chkDebugLog = fragment.findViewById(R.id.chkDebugLog);

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

        statusOpenScaleConnection = new StatusView(getContext(), getResources().getString(R.string.txt_openScale_connection));
        btnInstallOpenScale = statusOpenScaleConnection.addButton(getResources().getString(R.string.txt_install_openScale));
        btnPermissionOpenScale = statusOpenScaleConnection.addButton(getResources().getString(R.string.txt_request_openScale_permission));

        statusOpenScaleUser = new StatusView(getContext(),getResources().getString(R.string.txt_openScale_user));
        spinScaleUer = statusOpenScaleUser.addSpinner();

        statusGoogleFitSync = new StatusView(getContext(), getResources().getString(R.string.txt_googlefit_status));
        statusMQTTSync = new StatusView(getContext(), getResources().getString(R.string.txt_mqtt_status));

        overviewMainLayout.addView(statusGoogleFitSync);
        overviewMainLayout.addView(statusMQTTSync);
        overviewMainLayout.addView(statusOpenScaleConnection);
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

        boolean googleFitStatus = prefs.getBoolean("enableGoogleFit", true);
        boolean mqttStatus = prefs.getBoolean("enableMQTT", false);

        if (googleFitStatus) {
            statusGoogleFitSync.setCheck(true, "GoogleFit is enabled");
        } else {
            statusGoogleFitSync.setCheck(false, "GoogleFit is disabled");
        }

        if (mqttStatus) {
            statusMQTTSync.setCheck(true, "MQTT sync is enabled");
        } else {
            statusMQTTSync.setCheck(false, "MQTT sync is disabled");
        }
    }

    private boolean checkOpenScaleConnection() {
        String packageName = prefs.getString("openScalePackageName", "com.health.openscale.pro");

        if (isPackageInstalled(packageName)) {
            if (ActivityCompat.checkSelfPermission(getContext(), packageName + ".READ_WRITE_DATA") == PackageManager.PERMISSION_GRANTED) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());

                if (openScaleProvider.checkVersion()) {
                    statusOpenScaleConnection.setCheck(true, getResources().getString(R.string.txt_openScale_version_is_ok) + "(" + packageName + ")");
                    return true;
                } else {
                    statusOpenScaleConnection.setCheck(false, getResources().getString(R.string.txt_openScale_version_too_old));
                }
            } else {
                statusOpenScaleConnection.setCheck(false, getResources().getString(R.string.txt_openScale_permission_not_granted));
            }
        } else {
            statusOpenScaleConnection.setCheck(false, getResources().getString(R.string.txt_openScale_is_not_installed));
        }

        return false;
    }

    private boolean isPackageInstalled(String packageName) {
        Intent intent = getActivity().getPackageManager().getLaunchIntentForPackage(packageName);

        if (intent == null) {
            return false;
        }

        List<ResolveInfo> list = getActivity().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        return !list.isEmpty();
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
                statusOpenScaleConnection.setCheck(true, getResources().getString(R.string.txt_openScale_connection_successful));
                checkOpenScaleUsers();
            } else {
                statusOpenScaleConnection.setCheck(false, getResources().getString(R.string.txt_openScale_connection_failed));
            }
        }
    }
}

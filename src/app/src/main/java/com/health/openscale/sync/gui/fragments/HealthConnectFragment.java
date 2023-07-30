/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;
import com.health.openscale.sync.core.sync.HealthConnectSync;
import com.health.openscale.sync.gui.view.StatusView;

import timber.log.Timber;

public class HealthConnectFragment extends Fragment {
    private final int HEALTH_CONNECT_PERMISSIONS_REQUEST_CODE = 108;

    private SharedPreferences prefs;

    private LinearLayout healthConnectMainLayout;
    private StatusView statusViewHealthConnect;
    private Switch toggleHealthConnectSync;
    private Button btnHealthConnectSignIn;

    private Button btnHealthConnectSync;
    private ProgressBar progressBar;

    private HealthConnectSync healthConnectSync;

    public HealthConnectFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_health_connect, container, false);

        healthConnectSync = new HealthConnectSync(getContext());

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        healthConnectMainLayout = fragment.findViewById(R.id.healthConnectMainLayout);
        toggleHealthConnectSync = fragment.findViewById(R.id.toggleHealthConnect);
        btnHealthConnectSync = fragment.findViewById(R.id.btnHealthConnectSync);
        progressBar = fragment.findViewById(R.id.progressBar);

        statusViewHealthConnect = new StatusView(getContext(), getResources().getString(R.string.txt_healthConnect_status));
        btnHealthConnectSignIn = statusViewHealthConnect.addButton(getResources().getString(R.string.txt_google_sign_in));

        healthConnectMainLayout.addView(statusViewHealthConnect);

        toggleHealthConnectSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (buttonView.isPressed()) {
                    prefs.edit().putBoolean("enableHealthConnect", isChecked).commit();
                    checkStatusHealthConnect();
                }
            }
        });

        btnHealthConnectSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d(getResources().getString(R.string.txt_google_sign_in_button_clicked));

                requestGoogleFitPermission();
            }
        });

        btnHealthConnectSync.setOnClickListener(new onFullSyncClick());

        checkStatusHealthConnect();

        return fragment;
    }

    private class onFullSyncClick implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (checkStatusHealthConnect()) {
                btnHealthConnectSync.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);

                final OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());
                final GoogleFitSync googleFitSync = new GoogleFitSync(getContext());

                final int openScaleUserId = prefs.getInt("openScaleUserId", 0);

                Timber.d(getResources().getString(R.string.txt_manual_sync_openScale_googleFit));

                for (ScaleMeasurement openScaleMeasurement : openScaleProvider.getMeasurements(openScaleUserId)) {
                    Timber.d("openScale measurement " +  openScaleMeasurement + " added to GoogleFit");

                    googleFitSync.insert(openScaleMeasurement);
                }

                Timber.d(getResources().getString(R.string.txt_manual_sync_googleFit_openScale));

                Task<DataReadResponse> googleFitReadRequest = googleFitSync.queryMeasurements();

                if (googleFitReadRequest != null) {
                    googleFitReadRequest.addOnCompleteListener(new OnCompleteListener<DataReadResponse>() {
                        @Override
                        public void onComplete(@NonNull Task<DataReadResponse> task) {
                            for (ScaleMeasurement googleFitMeasurement : googleFitSync.getQueryMeasurementsResults()) {
                                Timber.d("GoogleFit measurement " + googleFitMeasurement + " added to openScale");

                                openScaleProvider.insertMeasurement(googleFitMeasurement.getDate(), googleFitMeasurement.getWeight(), openScaleUserId);
                            }
                            progressBar.setVisibility(View.GONE);
                            btnHealthConnectSync.setEnabled(true);
                        }
                    }).addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            progressBar.setVisibility(View.GONE);
                            btnHealthConnectSync.setEnabled(true);
                            Timber.d("can't get GoogleFit measurements " + e.getMessage());
                        }
                    });
                }
            }
        }
    }

    private boolean checkStatusHealthConnect() {
        if (healthConnectSync.isEnable()) {
            toggleHealthConnectSync.setChecked(true);
            btnHealthConnectSync.setEnabled(true);

            statusViewHealthConnect.setEnable(true);

            healthConnectSync.checkStatus(statusViewHealthConnect);
            return true;
        } else {
            toggleHealthConnectSync.setChecked(false);
            btnHealthConnectSync.setEnabled(false);

            statusViewHealthConnect.setEnable(false);
        }

        return false;
    }

    private void requestGoogleFitPermission() {
        GoogleSignIn.requestPermissions(
                this,
                HEALTH_CONNECT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(getContext()),
                GoogleFitSync.getFitnessOptions());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == HEALTH_CONNECT_PERMISSIONS_REQUEST_CODE) {

            if (resultCode == Activity.RESULT_OK) {
                statusViewHealthConnect.setCheck(true, getResources().getString(R.string.txt_googleFit_permission_granted));
            } else if (resultCode == Activity.RESULT_CANCELED) {
                statusViewHealthConnect.setCheck(false, getResources().getString(R.string.txt_googleFit_permission_not_granted));
            }
        }
    }

}

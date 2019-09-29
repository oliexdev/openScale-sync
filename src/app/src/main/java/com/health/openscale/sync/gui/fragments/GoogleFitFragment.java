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
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;
import com.health.openscale.sync.gui.view.StatusView;

import timber.log.Timber;

public class GoogleFitFragment extends Fragment {
    private final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 100;

    private SharedPreferences prefs;

    private LinearLayout googleFitMainLayout;
    private StatusView statusGoogleSignIn;
    private Switch toggleGoogleSync;
    private Button btnGoogleSignIn;

    private Button btnGoogleFitSync;
    private ProgressBar progressBar;


    public GoogleFitFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_google_fit, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        googleFitMainLayout = fragment.findViewById(R.id.googleFitMainLayout);
        toggleGoogleSync = fragment.findViewById(R.id.toggleGoogleFit);
        btnGoogleFitSync = fragment.findViewById(R.id.btnGoogleFitSync);
        progressBar = fragment.findViewById(R.id.progressBar);

        statusGoogleSignIn = new StatusView(getContext(), getResources().getString(R.string.txt_googlefit_status));
        btnGoogleSignIn = statusGoogleSignIn.addButton(getResources().getString(R.string.txt_google_sign_in));

        googleFitMainLayout.addView(statusGoogleSignIn);

        toggleGoogleSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (buttonView.isPressed()) {
                    prefs.edit().putBoolean("enableGoogleFit", isChecked).commit();
                    checkStatusGoogleFit();
                }
            }
        });

        btnGoogleSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d(getResources().getString(R.string.txt_google_sign_in_button_clicked));

                requestGoogleFitPermission();
            }
        });

        btnGoogleFitSync.setOnClickListener(new onFullSyncClick());

        checkStatusGoogleFit();

        return fragment;
    }

    private class onFullSyncClick implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (checkStatusGoogleFit()) {
                btnGoogleFitSync.setEnabled(false);
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
                            btnGoogleFitSync.setEnabled(true);
                        }
                    }).addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            progressBar.setVisibility(View.GONE);
                            btnGoogleFitSync.setEnabled(true);
                            Timber.d("can't get GoogleFit measurements " + e.getMessage());
                        }
                    });
                }
            }
        }
    }

    private boolean checkStatusGoogleFit() {
        if (prefs.getBoolean("enableGoogleFit", true)) {
            toggleGoogleSync.setChecked(true);
            btnGoogleFitSync.setEnabled(true);

            statusGoogleSignIn.setEnable(true);

            checkGoogleFitConnection();
            return true;
        } else {
            toggleGoogleSync.setChecked(false);
            btnGoogleFitSync.setEnabled(false);

            statusGoogleSignIn.setEnable(false);
        }

        return false;
    }

    private void checkGoogleFitConnection() {
        if (GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(getContext()), GoogleFitSync.getFitnessOptions())) {
            if (GoogleSignIn.getLastSignedInAccount(getContext()).isExpired()) {
                statusGoogleSignIn.setCheck(false, getResources().getString(R.string.txt_google_sign_in_expired));

                GoogleSignIn.getClient(getContext(), GoogleSignInOptions.DEFAULT_SIGN_IN).
                        silentSignIn().addOnSuccessListener(new OnSuccessListener<GoogleSignInAccount>() {
                    @Override
                    public void onSuccess(GoogleSignInAccount googleSignInAccount) {
                        statusGoogleSignIn.setCheck(true, getResources().getString(R.string.txt_googleFit_successful_sign_in));
                    }
                }).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        statusGoogleSignIn.setCheck(false, getResources().getString(R.string.txt_google_sign_in_failed));
                    }
                });

            } else {
                statusGoogleSignIn.setCheck(true, getResources().getString(R.string.txt_googleFit_successful_sign_in));
            }
        } else {
            statusGoogleSignIn.setCheck(false, getResources().getString(R.string.txt_google_permission_not_granted));
        }
    }

    private void requestGoogleFitPermission() {
        GoogleSignIn.requestPermissions(
                this,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(getContext()),
                GoogleFitSync.getFitnessOptions());
    }



    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {

            if (resultCode == Activity.RESULT_OK) {
                statusGoogleSignIn.setCheck(true, getResources().getString(R.string.txt_googleFit_permission_granted));
            } else if (resultCode == Activity.RESULT_CANCELED) {
                statusGoogleSignIn.setCheck(false, getResources().getString(R.string.txt_googleFit_permission_not_granted));
            }
        }
    }

}

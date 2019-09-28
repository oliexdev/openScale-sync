/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import android.app.Activity;
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
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
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
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class GoogleFitFragment extends Fragment {
    private final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 100;
    private final int OPENSCALE_PERMISSIONS_REQUEST_CODE = 101;

    private SharedPreferences prefs;

    private LinearLayout statusMainLayout;
    private StatusView statusGoogleSignIn;
    private StatusView statusOpenScaleConnection;
    private StatusView statusOpenScaleUser;
    private Switch toggleGoogleSync;
    private Button btnGoogleSignIn;
    private Button btnInstallOpenScale;
    private Button btnPermissionOpenScale;
    private Button btnManualSync;
    private Spinner spinScaleUer;
    private ArrayAdapter<String> spinScaleUserAdapter;
    private ProgressBar progressBar;

    private String openScalePackageName;

    public GoogleFitFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_google_fit, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        openScalePackageName = "com.health.openscale.pro";

        if (!isPackageInstalled(openScalePackageName)) {
            openScalePackageName = "com.health.openscale";

            if (!isPackageInstalled(openScalePackageName)) {
                openScalePackageName = "com.health.openscale.light";

                if (!isPackageInstalled(openScalePackageName)) {
                    Timber.d("no openScale version found on startup");
                }
            }
        }

        statusMainLayout = fragment.findViewById(R.id.statusMainLayout);
        toggleGoogleSync = fragment.findViewById(R.id.toggleGoogleFit);
        btnManualSync = fragment.findViewById(R.id.btnManualSync);
        progressBar = fragment.findViewById(R.id.progressBar);

        statusGoogleSignIn = new StatusView(getContext(), getResources().getString(R.string.txt_google_sign_in));
        btnGoogleSignIn = statusGoogleSignIn.addButton(getResources().getString(R.string.txt_google_sign_in));

        statusOpenScaleConnection = new StatusView(getContext(), getResources().getString(R.string.txt_openScale_connection));
        btnInstallOpenScale = statusOpenScaleConnection.addButton(getResources().getString(R.string.txt_install_openScale));
        btnPermissionOpenScale = statusOpenScaleConnection.addButton(getResources().getString(R.string.txt_request_openScale_permission));

        statusOpenScaleUser = new StatusView(getContext(),getResources().getString(R.string.txt_openScale_user));
        spinScaleUer = statusOpenScaleUser.addSpinner();

        statusMainLayout.addView(statusGoogleSignIn);
        statusMainLayout.addView(statusOpenScaleConnection);
        statusMainLayout.addView(statusOpenScaleUser);

        spinScaleUserAdapter = new ArrayAdapter<>(getContext(), R.layout.spinner_item, new ArrayList<String>());
        spinScaleUer.setAdapter(spinScaleUserAdapter);

        spinScaleUer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (checkOpenScaleConnection(openScalePackageName)) {
                    OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext(), openScalePackageName);

                    List<OpenScaleProvider.OpenScaleUser> openScaleUsers = openScaleProvider.getUsers();

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

        toggleGoogleSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("enableGoogleFit", isChecked).commit();
                checkIsEnabled();
            }
        });

        btnGoogleSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d(getResources().getString(R.string.txt_google_sign_in_button_clicked));

                requestGoogleFitPermission();
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

                requestOpenScalePermission(openScalePackageName);
            }
        });

        btnManualSync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkIsEnabled()) {
                    btnManualSync.setEnabled(false);
                    progressBar.setVisibility(View.VISIBLE);

                    final OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext(), openScalePackageName);
                    final GoogleFitSync googleFitSync = new GoogleFitSync(getContext());

                    final int openScaleUserId = prefs.getInt("openScaleUserId", 0);

                    Timber.d(getResources().getString(R.string.txt_manual_sync_openScale_googleFit));

                    for (OpenScaleProvider.OpenScaleMeasurement openScaleMeasurement : openScaleProvider.getMeasurements(openScaleUserId)) {
                        Timber.d("openScale measurement " +  openScaleMeasurement + " added to GoogleFit");

                        googleFitSync.insertMeasurement(openScaleMeasurement.date, openScaleMeasurement.weight);
                    }

                    Timber.d(getResources().getString(R.string.txt_manual_sync_googleFit_openScale));

                    Task<DataReadResponse> googleFitReadRequest = googleFitSync.queryMeasurements();

                    if (googleFitReadRequest != null) {
                        googleFitReadRequest.addOnCompleteListener(new OnCompleteListener<DataReadResponse>() {
                            @Override
                            public void onComplete(@NonNull Task<DataReadResponse> task) {
                                for (GoogleFitSync.GoogleFitMeasurement googleFitMeasurement : googleFitSync.getQueryMeasurementsResults()) {
                                    Timber.d("GoogleFit measurement " + googleFitMeasurement + " added to openScale");

                                    openScaleProvider.insertMeasurement(googleFitMeasurement.date, googleFitMeasurement.weight, openScaleUserId);
                                }
                                progressBar.setVisibility(View.GONE);
                                btnManualSync.setEnabled(true);
                            }
                        }).addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                progressBar.setVisibility(View.GONE);
                                btnManualSync.setEnabled(true);
                                Timber.d("can't get GoogleFit measurements " + e.getMessage());
                            }
                        });
                    }
                }
            }
        });

        checkIsEnabled();

        return fragment;
    }

    private boolean checkIsEnabled() {
        if (prefs.getBoolean("enableGoogleFit", true)) {
            toggleGoogleSync.setChecked(true);
            btnManualSync.setEnabled(true);

            statusGoogleSignIn.setEnable(true);
            statusOpenScaleConnection.setEnable(true);
            statusOpenScaleUser.setEnable(true);

            checkGoogleFitConnection();
            if (checkOpenScaleConnection(openScalePackageName)) {
                checkOpenScaleUsers(openScalePackageName);
            }
            return true;
        } else {
            toggleGoogleSync.setChecked(false);
            btnManualSync.setEnabled(false);

            statusGoogleSignIn.setEnable(false);
            statusOpenScaleConnection.setEnable(false);
            statusOpenScaleUser.setEnable(false);
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

    private boolean checkOpenScaleConnection(String packageName) {
        if (isPackageInstalled(packageName)) {
            if (ActivityCompat.checkSelfPermission(getContext(), packageName + ".READ_WRITE_DATA") == PackageManager.PERMISSION_GRANTED) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext(), packageName);

                if (openScaleProvider.checkVersion()) {
                    statusOpenScaleConnection.setCheck(true, getResources().getString(R.string.txt_openScale_version_is_ok) + "(" + openScalePackageName + ")");
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

    private boolean checkOpenScaleUsers(String packageName) {
        OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext(), packageName);

        if (openScaleProvider.getUsers().isEmpty()) {
            statusOpenScaleUser.setCheck(false, getResources().getString(R.string.txt_openScale_user_not_found));
        } else {
            int scaleUserId = prefs.getInt("openScaleUserId", 0);

            spinScaleUserAdapter.clear();

            int spinPos = 0;

            for (OpenScaleProvider.OpenScaleUser openScaleUser : openScaleProvider.getUsers()) {
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


    private boolean isPackageInstalled(String packageName) {
        Intent intent = getActivity().getPackageManager().getLaunchIntentForPackage(packageName);

        if (intent == null) {
            return false;
        }

        List<ResolveInfo> list = getActivity().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        return !list.isEmpty();
    }

    private void requestGoogleFitPermission() {
        GoogleSignIn.requestPermissions(
                this,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(getContext()),
                GoogleFitSync.getFitnessOptions());
    }

    private void requestOpenScalePermission(String packageName) {
        requestPermissions(
                new String[]{packageName + ".READ_WRITE_DATA"},
                OPENSCALE_PERMISSIONS_REQUEST_CODE);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == OPENSCALE_PERMISSIONS_REQUEST_CODE) {
            if(grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusOpenScaleConnection.setCheck(true, getResources().getString(R.string.txt_openScale_connection_successful));
                checkOpenScaleUsers(openScalePackageName);
            } else {
                statusOpenScaleConnection.setCheck(false, getResources().getString(R.string.txt_openScale_connection_failed));
            }
        }
    }

}

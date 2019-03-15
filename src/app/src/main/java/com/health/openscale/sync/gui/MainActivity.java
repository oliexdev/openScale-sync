/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    public static final int COLOR_RED = Color.parseColor("#FF4444");
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_GRAY = Color.parseColor("#d3d3d3");

    private final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 100;
    private final int OPENSCALE_PERMISSIONS_REQUEST_CODE = 101;

    private Switch toggleGoogleSync;
    private Button btnGoogleSignIn;
    private Button btnInstallOpenScale;
    private Button btnPermissionOpenScale;
    private Button btnManualSync;
    private ImageView imgGoogleSignIn;
    private ImageView imgOpenScaleConnection;
    private ImageView imgScaleUser;
    private TextView txtGoogleSignIn;
    private TextView txtOpenScaleConnection;
    private TextView txtScaleUser;
    private Spinner spinScaleUer;
    private ArrayAdapter<String> spinScaleUserAdapter;

    private SharedPreferences prefs;

    private String openScalePackageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        openScalePackageName = "com.health.openscale";

        Timber.plant(new Timber.DebugTree());

        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setTitle("  openScale Sync");
        getSupportActionBar().setIcon(R.drawable.ic_launcher_openscale_sync);
        getSupportActionBar().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#000000")));

        toggleGoogleSync = findViewById(R.id.toggleGoogleFit);
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn);
        btnInstallOpenScale = findViewById(R.id.btnInstallOpenScale);
        btnPermissionOpenScale= findViewById(R.id.btnOpenScalePermission);
        btnManualSync = findViewById(R.id.btnManualSync);
        imgOpenScaleConnection = findViewById(R.id.imgOpenScaleConnection);
        imgGoogleSignIn = findViewById(R.id.imgGoogleSignIn);
        imgScaleUser = findViewById(R.id.imgScaleUser);
        txtGoogleSignIn = findViewById(R.id.txtGoogleSignIn);
        txtOpenScaleConnection = findViewById(R.id.txtOpenScaleConnection);
        txtScaleUser = findViewById(R.id.txtScaleSyncUser);
        spinScaleUer = findViewById(R.id.spinScaleUser);

        spinScaleUserAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, new ArrayList<String>());
        spinScaleUer.setAdapter(spinScaleUserAdapter);

        spinScaleUer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(getApplicationContext(), openScalePackageName);

                int openScaleUserId = openScaleProvider.getUsers().get(position).userid;

                prefs.edit().putInt("openScaleUserId", openScaleUserId).commit();
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
                Timber.d("Google sign in button clicked");

                requestGoogleFitPermission();
            }
        });

        btnInstallOpenScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d("Install openScale button clicked");

                Intent goToMarket = new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse("market://details?id=com.health.openscale"));
                startActivity(goToMarket);
            }
        });

        btnPermissionOpenScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d("Permission openScale button clicked");

                requestOpenScalePermission(openScalePackageName);
            }
        });

        checkIsEnabled();
        checkGoogleFitConnection();
        checkOpenScaleConnection(openScalePackageName);
        checkOpenScaleUsers(openScalePackageName);
    }

    private boolean checkIsEnabled() {
        if (prefs.getBoolean("enableGoogleFit", true)) {
            toggleGoogleSync.setChecked(true);
            btnGoogleSignIn.setEnabled(true);
            btnInstallOpenScale.setEnabled(true);
            btnPermissionOpenScale.setEnabled(true);
            btnManualSync.setEnabled(true);
            imgGoogleSignIn.setEnabled(true);
            imgOpenScaleConnection.setEnabled(true);
            imgScaleUser.setEnabled(true);
            txtGoogleSignIn.setEnabled(true);
            txtOpenScaleConnection.setEnabled(true);
            txtScaleUser.setEnabled(true);
            spinScaleUer.setEnabled(true);
            return true;
        } else {
            toggleGoogleSync.setChecked(false);
            btnGoogleSignIn.setEnabled(false);
            btnInstallOpenScale.setEnabled(false);
            btnPermissionOpenScale.setEnabled(false);
            btnManualSync.setEnabled(false);
            imgGoogleSignIn.setEnabled(false);
            imgOpenScaleConnection.setEnabled(false);
            imgScaleUser.setEnabled(false);
            txtGoogleSignIn.setEnabled(false);
            txtOpenScaleConnection.setEnabled(false);
            txtScaleUser.setEnabled(false);
            spinScaleUer.setEnabled(false);
        }

        return false;
    }

    private boolean checkGoogleFitConnection() {
        if (GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(this), GoogleFitSync.getFitnessOptions())) {
            imgGoogleSignIn.setImageResource(R.drawable.ic_status_check);
            btnGoogleSignIn.setVisibility(View.GONE);
            return true;
        } else {
            imgGoogleSignIn.setImageResource(R.drawable.ic_status_error);
            btnGoogleSignIn.setVisibility(View.VISIBLE);
            Timber.d("GoogleFit permission not granted");
        }

        return false;
    }

    private boolean checkOpenScaleConnection(String packageName) {
        if (isPackageInstalled(packageName)) {
            btnInstallOpenScale.setVisibility(View.GONE);

            if (ActivityCompat.checkSelfPermission(this, packageName + ".READ_WRITE_DATA") == PackageManager.PERMISSION_GRANTED) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(this, packageName);

                if (openScaleProvider.checkVersion()) {
                    Timber.d("openScale version is ok");
                    btnPermissionOpenScale.setVisibility(View.GONE);

                    imgOpenScaleConnection.setImageResource(R.drawable.ic_status_check);
                    OpenScaleProvider.OpenScaleUser openScaleUser = openScaleProvider.getUsers().get(0);

                    Timber.d("openScale users " + openScaleProvider.getUsers());
                    Timber.d("openScale measurements " + openScaleProvider.getMeasurements(openScaleUser.userid));

                    return true;
                } else {
                    Timber.d("openScale version is too old");
                    btnPermissionOpenScale.setVisibility(View.VISIBLE);
                }
            } else {
                imgOpenScaleConnection.setImageResource(R.drawable.ic_status_error);
                btnPermissionOpenScale.setVisibility(View.VISIBLE);
                Timber.d("openScale permission not granted");
            }
        } else {
            imgOpenScaleConnection.setImageResource(R.drawable.ic_status_error);
            btnInstallOpenScale.setVisibility(View.VISIBLE);
            Timber.d("openScale is not installed");
        }

        return false;
    }

    private boolean checkOpenScaleUsers(String packageName) {
        OpenScaleProvider openScaleProvider = new OpenScaleProvider(this, packageName);

        if (openScaleProvider.getUsers().isEmpty()) {
            imgScaleUser.setImageResource(R.drawable.ic_status_error);
            spinScaleUserAdapter.add("error no openScale exist");
        } else {
            imgScaleUser.setImageResource(R.drawable.ic_status_check);

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
        }

        return true;
    }

    private void requestGoogleFitPermission() {
        GoogleSignIn.requestPermissions(
                this,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(getApplicationContext()),
                GoogleFitSync.getFitnessOptions());
    }

    private void requestOpenScalePermission(String packageName) {
        ActivityCompat.requestPermissions(
                this,
                new String[]{packageName + ".READ_WRITE_DATA"},
                OPENSCALE_PERMISSIONS_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                checkGoogleFitConnection();
                Timber.d("Successful login to Google");
            } else if (resultCode == Activity.RESULT_CANCELED) {
                checkGoogleFitConnection();
                Timber.d("Can't login to Google");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == OPENSCALE_PERMISSIONS_REQUEST_CODE) {
            if(grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkOpenScaleConnection(openScalePackageName);
                Timber.d("Successful connect to openScale");
            } else {
                checkOpenScaleConnection(openScalePackageName);
                Timber.d("Can't connect to openScale");
            }
        }
    }


    private boolean isPackageInstalled(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);

        if (intent == null) {
            return false;
        }

        List<ResolveInfo> list = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        return !list.isEmpty();
    }
}

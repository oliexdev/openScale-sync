/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.health.openscale.sync.BuildConfig;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.GoogleFitSync;
import com.health.openscale.sync.gui.utils.DebugTree;
import com.health.openscale.sync.gui.view.StatusView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import cat.ereza.customactivityoncrash.config.CaocConfig;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_GRAY = Color.parseColor("#d3d3d3");

    private final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 100;
    private final int OPENSCALE_PERMISSIONS_REQUEST_CODE = 101;
    private final int DEBUG_WRITE_PERMISSIONS_REQUEST_CODE = 102;

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

    private SharedPreferences prefs;
    private DebugTree debugTree;
    private Menu actionMenu;

    private String openScalePackageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CaocConfig.Builder.create()
                .trackActivities(false)
                .apply();

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        debugTree = new DebugTree();

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

        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setTitle("  openScale Sync");
        getSupportActionBar().setIcon(R.drawable.ic_launcher_openscale_sync);
        getSupportActionBar().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#000000")));

        statusMainLayout = findViewById(R.id.statusMainLayout);
        toggleGoogleSync = findViewById(R.id.toggleGoogleFit);
        btnManualSync = findViewById(R.id.btnManualSync);
        progressBar = findViewById(R.id.progressBar);

        statusGoogleSignIn = new StatusView(this, "Google sign In");
        btnGoogleSignIn = statusGoogleSignIn.addButton("Google sign in");

        statusOpenScaleConnection = new StatusView(this, "openScale connection");
        btnInstallOpenScale = statusOpenScaleConnection.addButton("Install openScale");
        btnPermissionOpenScale = statusOpenScaleConnection.addButton("Request openScale permission");

        statusOpenScaleUser = new StatusView(this,"openScale user");
        spinScaleUer = statusOpenScaleUser.addSpinner();

        statusMainLayout.addView(statusGoogleSignIn);
        statusMainLayout.addView(statusOpenScaleConnection);
        statusMainLayout.addView(statusOpenScaleUser);

        spinScaleUserAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, new ArrayList<String>());
        spinScaleUer.setAdapter(spinScaleUserAdapter);

        spinScaleUer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (checkOpenScaleConnection(openScalePackageName)) {
                    OpenScaleProvider openScaleProvider = new OpenScaleProvider(getApplicationContext(), openScalePackageName);

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

        btnManualSync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkIsEnabled()) {
                    btnManualSync.setEnabled(false);
                    progressBar.setVisibility(View.VISIBLE);

                    final OpenScaleProvider openScaleProvider = new OpenScaleProvider(getApplicationContext(), openScalePackageName);
                    final GoogleFitSync googleFitSync = new GoogleFitSync(getApplicationContext());

                    final int openScaleUserId = prefs.getInt("openScaleUserId", 0);

                    Timber.d("Manual sync openScale measurements -> GoogleFit");

                    for (OpenScaleProvider.OpenScaleMeasurement openScaleMeasurement : openScaleProvider.getMeasurements(openScaleUserId)) {
                        Timber.d("openScale measurement " +  openScaleMeasurement + " added to GoogleFit");

                        googleFitSync.insertMeasurement(openScaleMeasurement.date, openScaleMeasurement.weight);
                    }

                    Timber.d("Manual sync GoogleFit measurements -> openScale");

                    Task<DataReadResponse>  googleFitReadRequest = googleFitSync.queryMeasurements();

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
        if (GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(this), GoogleFitSync.getFitnessOptions())) {
            if (GoogleSignIn.getLastSignedInAccount(this).isExpired()) {
                statusGoogleSignIn.setCheck(false, "Google sign in expired");

                GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).
                        silentSignIn().addOnSuccessListener(new OnSuccessListener<GoogleSignInAccount>() {
                    @Override
                    public void onSuccess(GoogleSignInAccount googleSignInAccount) {
                        statusGoogleSignIn.setCheck(true, "GoogleFit successful silent sign in");
                    }
                }).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        statusGoogleSignIn.setCheck(false, "GoogleFit failure silent sign in");
                    }
                });

            } else {
                statusGoogleSignIn.setCheck(true, "GoogleFit successful sign in");
            }
        } else {
            statusGoogleSignIn.setCheck(false, "Google permission not granted");
        }
    }

    private boolean checkOpenScaleConnection(String packageName) {
        if (isPackageInstalled(packageName)) {
            if (ActivityCompat.checkSelfPermission(this, packageName + ".READ_WRITE_DATA") == PackageManager.PERMISSION_GRANTED) {
                OpenScaleProvider openScaleProvider = new OpenScaleProvider(this, packageName);

                if (openScaleProvider.checkVersion()) {
                    statusOpenScaleConnection.setCheck(true, "openScale version " + openScalePackageName + " is ok");
                    return true;
                } else {
                    statusOpenScaleConnection.setCheck(false, "openScale version " + openScalePackageName + " is too old");
                }
            } else {
                statusOpenScaleConnection.setCheck(false, "openScale permission not granted");
            }
        } else {
            statusOpenScaleConnection.setCheck(false, "openScale is not installed");
        }

        return false;
    }

    private boolean checkOpenScaleUsers(String packageName) {
        OpenScaleProvider openScaleProvider = new OpenScaleProvider(this, packageName);

        if (openScaleProvider.getUsers().isEmpty()) {
            statusOpenScaleUser.setCheck(false, "no openScale exist");
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
            statusOpenScaleUser.setCheck(true, "openScale user exist " + spinScaleUer.getSelectedItem());
        }

        return true;
    }

    private void requestGoogleFitPermission() {
        GoogleSignIn.requestPermissions(
                this,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(this),
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
                statusGoogleSignIn.setCheck(true, "GoogleFit permission granted");
            } else if (resultCode == Activity.RESULT_CANCELED) {
                statusGoogleSignIn.setCheck(false, "GoogleFit permission denied");
            }
        }

        if (requestCode == DEBUG_WRITE_PERMISSIONS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            debugTree.startLogTo(this, data.getData());
            MenuItem actionDebug = actionMenu.findItem(R.id.actionDebug);
            actionDebug.setChecked(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == OPENSCALE_PERMISSIONS_REQUEST_CODE) {
            if(grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusOpenScaleConnection.setCheck(true, "Successful connect to openScale");
            } else {
                statusOpenScaleConnection.setCheck(false, "Can't connect to openScale");
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_menu, menu);

        actionMenu = menu;

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.actionAbout:
                final SpannableString abouotMsg = new SpannableString("Copyright (C) 2019 All Rights Reserved\nby olie.xdev@googlemail.com\n\nWebsite https://github.com/oliexdev/openScale");
                Linkify.addLinks(abouotMsg, Linkify.ALL);

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("openScale Sync " + String.format("v%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                        .setMessage(abouotMsg)
                        .setIcon(R.drawable.ic_launcher_openscale_sync)
                        .setPositiveButton("Ok", null)
                        .create();

                dialog.show();

                ((TextView)dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
                break;
            case R.id.actionDebug:
                if (item.isChecked()) {
                    debugTree.close();
                    item.setChecked(false);
                } else {
                    startActivityForResult(debugTree.requestDebugIntent(), DEBUG_WRITE_PERMISSIONS_REQUEST_CODE);
                }
                break;
            default:
                Timber.e("no action item found");
                break;
        }

        return super.onOptionsItemSelected(item);
    }
}

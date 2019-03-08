/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.sync.GoogleFitSync;

import androidx.appcompat.app.AppCompatActivity;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 100;

    private Button testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Timber.plant(new Timber.DebugTree());

        testButton = findViewById(R.id.testButton);

        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Timber.d("Test Button clicked");

                doGoogleSignIn();
            }
        });
    }

    private void doGoogleSignIn() {
        GoogleSignIn.requestPermissions(
                this,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(getApplicationContext()),
                GoogleFitSync.getFitnessOptions());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
                Timber.d("Successfull login to Google");
            }
        }
    }
}

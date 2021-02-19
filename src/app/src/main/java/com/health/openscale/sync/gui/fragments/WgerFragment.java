/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;

import androidx.fragment.app.Fragment;

import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.WgerSync;
import com.health.openscale.sync.gui.view.StatusView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

public class WgerFragment extends Fragment {
    private SharedPreferences prefs;

    private LinearLayout wgerMainLayout;
    private Switch toggleWgerSync;
    private EditText txtApiKey;
    private Button btnSaveCredentials;
    private Button btnWgerSync;
    private ProgressBar progressBar;
    private StatusView statusViewWger;
    private WgerSync wgerSync;

    public WgerFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_wger, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        wgerSync = new WgerSync(getContext());

        wgerMainLayout = fragment.findViewById(R.id.wgerMainLayout);
        toggleWgerSync = fragment.findViewById(R.id.toggleWger);
        txtApiKey = fragment.findViewById(R.id.txtApiKey);
        btnSaveCredentials = fragment.findViewById(R.id.btnSaveCredentials);
        btnWgerSync = fragment.findViewById(R.id.btnWgerSync);
        progressBar = fragment.findViewById(R.id.progressBar);
        statusViewWger = new StatusView(getContext(), getResources().getString(R.string.txt_wger_status));

        wgerMainLayout.addView(statusViewWger);

        btnSaveCredentials.setEnabled(false);

        btnSaveCredentials.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v.isPressed()) {
                    if (txtApiKey.getText().toString().isEmpty()) {
                        txtApiKey.setError(getResources().getString(R.string.txt_mqtt_field_is_empty));
                    } else {
                        prefs.edit().putString("wgerApiKey", txtApiKey.getText().toString()).commit();
                    }

                    wgerSync.checkStatus(statusViewWger);
                }
            }
        });

        btnWgerSync.setOnClickListener(new onFullSyncClick());

        toggleWgerSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (buttonView.isPressed()) {
                    prefs.edit().putBoolean("enableWger", isChecked).commit();
                    checkStatusWger();
                }
            }
        });

        txtApiKey.setText(prefs.getString("wgerApiKey", "7faf59e0fac4aceb12d90c2f2603349d4de8471b"));

        txtApiKey.addTextChangedListener(new onTextChangeListener());

        checkStatusWger();

        return fragment;
    }

    private class onTextChangeListener implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            btnSaveCredentials.setEnabled(true);
        }
    }

    private class onFullSyncClick implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (checkStatusWger()) {
                Timber.d("Manual full sync to Wger");

                btnWgerSync.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);

                final OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());
                final int openScaleUserId = prefs.getInt("openScaleUserId", 0);

                Timber.d(getResources().getString(R.string.txt_manual_sync_openScale_wgerFit));

                for (ScaleMeasurement openScaleMeasurement : openScaleProvider.getMeasurements(openScaleUserId)) {
                    Timber.d("openScale measurement " + openScaleMeasurement + " added to wger");

                    wgerSync.insert(openScaleMeasurement);
                }

                Timber.d(getResources().getString(R.string.txt_manual_sync_wgerFit_openScale));

                Call<WgerSync.WgerWeightEntry> callWgerWeightEntryList = wgerSync.getWgerWeightList();

                callWgerWeightEntryList.enqueue(new Callback<WgerSync.WgerWeightEntry>() {
                    @Override
                    public void onResponse(Call<WgerSync.WgerWeightEntry> call, Response<WgerSync.WgerWeightEntry> response) {
                        if (response.isSuccessful()) {
                            Timber.d("successfully wger weight entry list");
                            List<WgerSync.WgerWeightEntry.WeightEntry> wgerWeightEntryList = response.body().results;

                            for (WgerSync.WgerWeightEntry.WeightEntry wgerWeightEntry : wgerWeightEntryList) {
                                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                                try {
                                    Date wgerDate = format.parse(wgerWeightEntry.date);
                                    openScaleProvider.insertMeasurement(wgerDate, wgerWeightEntry.weight, openScaleUserId);
                                } catch (ParseException e) {
                                    Timber.e("Error convert to wger date to date format");
                                }
                            }
                        } else {
                            Timber.d("get weight entry list error " + response.message());
                        }

                        progressBar.setVisibility(View.GONE);
                        btnWgerSync.setEnabled(true);
                    }

                    @Override
                    public void onFailure(Call<WgerSync.WgerWeightEntry> call, Throwable t) {
                        progressBar.setVisibility(View.GONE);
                        btnWgerSync.setEnabled(true);
                        Timber.e("get weight entry list failure " + t.getMessage());
                    }
                });
            }
        }
    }

    private boolean checkStatusWger() {
        if (wgerSync.isEnable()) {
            toggleWgerSync.setChecked(true);
            txtApiKey.setEnabled(true);
            btnWgerSync.setEnabled(true);

            wgerSync.checkStatus(statusViewWger);
            return true;
        } else {
            toggleWgerSync.setChecked(false);
            txtApiKey.setEnabled(false);
            btnWgerSync.setEnabled(false);
        }

        return false;
    }

}

/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments;

import android.content.SharedPreferences;
import android.os.AsyncTask;
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

import com.google.gson.Gson;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.core.provider.OpenScaleProvider;
import com.health.openscale.sync.core.sync.MQTTSync;
import com.health.openscale.sync.gui.view.StatusView;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import timber.log.Timber;

/**
 * test cloud under https://www.cloudmqtt.com/
 */

public class MQTTFragment extends Fragment {
    private SharedPreferences prefs;

    private LinearLayout mqttMainLayout;
    private Switch toggleMQTTSync;
    private EditText txtServer;
    private EditText txtUsername;
    private EditText txtPassword;
    private Button btnSaveCredentials;
    private Button btnMQTTSync;
    private ProgressBar progressBar;
    private StatusView statusViewMQTT;
    private MQTTSync mqttSync;

    private final String clientId = "openScaleSync";

    public MQTTFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View fragment = inflater.inflate(R.layout.fragment_mqtt, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        mqttSync = new MQTTSync(getContext());

        mqttMainLayout = fragment.findViewById(R.id.mqttMainLayout);
        toggleMQTTSync = fragment.findViewById(R.id.toggleMQTT);
        txtServer = fragment.findViewById(R.id.txtServer);
        txtUsername = fragment.findViewById(R.id.txtUsername);
        txtPassword = fragment.findViewById(R.id.txtPassword);
        btnSaveCredentials = fragment.findViewById(R.id.btnSaveCredentials);
        btnMQTTSync = fragment.findViewById(R.id.btnMQTTSync);
        progressBar = fragment.findViewById(R.id.progressBar);
        statusViewMQTT = new StatusView(getContext(), getResources().getString(R.string.txt_mqtt_status));

        mqttMainLayout.addView(statusViewMQTT);

        btnSaveCredentials.setEnabled(false);

        btnSaveCredentials.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v.isPressed()) {
                    if (txtServer.getText().toString().isEmpty()) {
                        txtServer.setError(getResources().getString(R.string.txt_mqtt_field_is_empty));
                    } else {
                        prefs.edit().putString("mqttServer", txtServer.getText().toString()).commit();
                    }

                    if (txtUsername.getText().toString().isEmpty()) {
                        txtUsername.setError(getResources().getString(R.string.txt_mqtt_field_is_empty));
                    } else {
                        prefs.edit().putString("mqttUsername", txtUsername.getText().toString()).commit();
                    }

                    if (txtPassword.getText().toString().isEmpty()) {
                        txtPassword.setError(getResources().getString(R.string.txt_mqtt_field_is_empty));
                    } else {
                        prefs.edit().putString("mqttPassword", txtPassword.getText().toString()).commit();
                    }

                    mqttSync.checkStatus(statusViewMQTT);
                }
            }
        });

        btnMQTTSync.setOnClickListener(new onFullSyncClick());

        toggleMQTTSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (buttonView.isPressed()) {
                    prefs.edit().putBoolean("enableMQTT", isChecked).commit();
                    checkStatusMQTT();
                }
            }
        });

        txtServer.setText(prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199"));
        txtUsername.setText(prefs.getString("mqttUsername", "ztntplvc"));
        txtPassword.setText(prefs.getString("mqttPassword", "IqdBs7XMr-Kr"));

        txtServer.addTextChangedListener(new onTextChangeListener());
        txtUsername.addTextChangedListener(new onTextChangeListener());
        txtPassword.addTextChangedListener(new onTextChangeListener());

        checkStatusMQTT();

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
            Timber.d("Manual full sync to MQTT");

            if (!mqttSync.getMqttAndroidClient().isConnected()) {
                Timber.e("MQTT is not connected");
                return;
            }

            btnMQTTSync.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);

            final OpenScaleProvider openScaleProvider = new OpenScaleProvider(getContext());

            final int openScaleUserId = prefs.getInt("openScaleUserId", 0);

            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    for (ScaleMeasurement openScaleMeasurement : openScaleProvider.getMeasurements(openScaleUserId)) {
                        Timber.d("openScale measurement " +  openScaleMeasurement + " added to MQTT");

                        Gson gson = new Gson();
                        String jsonMeasurement = gson.toJson(openScaleMeasurement);

                        MqttMessage msg = new MqttMessage();
                        msg.setPayload(jsonMeasurement.getBytes());
                        msg.setQos(2);

                        try {
                            mqttSync.getMqttAndroidClient().publish("openScaleSync/measurements/insert", msg);
                            Thread.sleep(10);
                        } catch (MqttException e) {
                            Timber.e(e.getMessage());
                        } catch (InterruptedException e) {
                            Timber.e(e.getMessage());
                        }
                    }

                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            btnMQTTSync.setEnabled(true);
                        }
                    });
                }
            });
        }
    }

    private boolean checkStatusMQTT() {
        if (mqttSync.isEnable()) {
            toggleMQTTSync.setChecked(true);
            txtServer.setEnabled(true);
            txtUsername.setEnabled(true);
            txtPassword.setEnabled(true);
            btnMQTTSync.setEnabled(true);

            mqttSync.checkStatus(statusViewMQTT);
            return true;
        } else {
            toggleMQTTSync.setChecked(false);
            txtServer.setEnabled(false);
            txtUsername.setEnabled(false);
            txtPassword.setEnabled(false);
            btnMQTTSync.setEnabled(false);
        }

        return false;
    }

}

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
import android.widget.Switch;

import androidx.fragment.app.Fragment;

import com.health.openscale.sync.R;
import com.health.openscale.sync.gui.view.StatusView;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

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
    private Button btnMQTT;
    private StatusView statusMQTT;

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

        mqttMainLayout = fragment.findViewById(R.id.mqttMainLayout);
        toggleMQTTSync = fragment.findViewById(R.id.toggleMQTT);
        txtServer = fragment.findViewById(R.id.txtServer);
        txtUsername = fragment.findViewById(R.id.txtUsername);
        txtPassword = fragment.findViewById(R.id.txtPassword);
        btnMQTT = fragment.findViewById(R.id.btnMQTT);
        statusMQTT = new StatusView(getContext(), getResources().getString(R.string.txt_mqtt_status_connection));

        mqttMainLayout.addView(statusMQTT);

        btnMQTT.setOnClickListener(new onFullScanClick());

        toggleMQTTSync.setOnCheckedChangeListener(new onToggleListener());

        txtServer.setText(prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199"));
        txtUsername.setText(prefs.getString("mqttUsername", "ztntplvc"));
        txtPassword.setText(prefs.getString("mqttPassword", "IqdBs7XMr-Kr"));

        txtServer.addTextChangedListener(new onTextChangeListener("mqttServer"));
        txtUsername.addTextChangedListener(new onTextChangeListener("mqttUsername"));
        txtPassword.addTextChangedListener(new onTextChangeListener("mqttPassword"));

        checkStatusMQTT();

        return fragment;
    }

    private class onToggleListener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            prefs.edit().putBoolean("enableMQTT", isChecked).commit();

            if (buttonView.isPressed()) {
                checkStatusMQTT();
            }
        }
    }

    private class onFullScanClick implements View.OnClickListener {

        @Override
        public void onClick(View v) {
        }
    }

    private class onTextChangeListener implements TextWatcher {
        private String prefKey;

        public onTextChangeListener(String key) {
            prefKey = key;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            prefs.edit().putString(prefKey, s.toString()).commit();
            checkMQTTConnection();
        }
    }

    private boolean checkStatusMQTT() {
        if (prefs.getBoolean("enableMQTT", false)) {
            toggleMQTTSync.setChecked(true);
            txtServer.setEnabled(true);
            txtUsername.setEnabled(true);
            txtPassword.setEnabled(true);
            btnMQTT.setEnabled(true);

            checkMQTTConnection();
            return true;
        } else {
            toggleMQTTSync.setChecked(false);
            txtServer.setEnabled(false);
            txtUsername.setEnabled(false);
            txtPassword.setEnabled(false);
            btnMQTT.setEnabled(false);
        }

        return false;
    }

    private void checkMQTTConnection() {
        Timber.d("Check MQTT Connection");
        final String mqttServer = txtServer.getText().toString();

        final MqttAndroidClient mqttAndroidClient = new MqttAndroidClient(getContext(), mqttServer, clientId);

            try {
                mqttAndroidClient.connect(getMQTTOptions(), null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        statusMQTT.setCheck(true, "Successful connected to " + mqttServer);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, final Throwable exception) {
                        statusMQTT.setCheck(false, "Failed to connect to " + mqttServer + " " + exception.toString());
                    }
                });
            } catch (MqttException ex) {
                statusMQTT.setCheck(false, ex.getMessage());
            }
    }

    private MqttConnectOptions getMQTTOptions() {
        final String mqttUsername = txtUsername.getText().toString();
        final String mqttPassword = txtPassword.getText().toString();

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(false);
        mqttConnectOptions.setCleanSession(true);
        mqttConnectOptions.setUserName(mqttUsername);
        mqttConnectOptions.setPassword(mqttPassword.toCharArray());

        return mqttConnectOptions;
    }
}

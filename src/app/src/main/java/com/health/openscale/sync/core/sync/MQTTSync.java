/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;

import com.google.gson.Gson;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.Date;

import timber.log.Timber;

public class MQTTSync extends ScaleMeasurementSync {
    private MqttAndroidClient mqttAndroidClient;
    private final String clientId = "openScaleSync";

    public MQTTSync(Context context) {
        super(context);
        final String mqttServer = prefs.getString("mqttServer", "noneMQTTServer");
        mqttAndroidClient = new MqttAndroidClient(context, mqttServer, clientId);
    }

    @Override
    public String getName() {
        return "MQTTSync";
    }

    @Override
    public boolean isEnable() {
        return prefs.getBoolean("enableMQTT", false);
    }

    @Override
    public void insert(final ScaleMeasurement measurement) {
        Gson gson = new Gson();
        String jsonMeasurement = gson.toJson(measurement);

        sendMessageToMQTT("measurements/insert", jsonMeasurement);
    }

    @Override
    public void delete(final Date date) {
        sendMessageToMQTT("measurements/delete", "{\"date\":" + date.getTime() + "}");
    }

    @Override
    public void clear() {
        sendMessageToMQTT("measurements/clear", "true");
    }

    @Override
    public void update(final ScaleMeasurement measurement) {
        Gson gson = new Gson();
        String jsonMeasurement = gson.toJson(measurement);

        sendMessageToMQTT("measurements/update", jsonMeasurement);
    }

    private void sendMessageToMQTT(final String topic, final String payload) {
        try {
            mqttAndroidClient.connect(getMQTTOptions(), null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    MqttMessage msg = new MqttMessage();
                    msg.setPayload(payload.getBytes());
                    msg.setQos(2);

                    try {
                        Timber.d("Succesful published on " + topic + " with message " + msg);
                        mqttAndroidClient.publish("openScaleSync/" + topic, msg);
                        mqttAndroidClient.disconnect();
                    } catch (MqttException ex) {
                        Timber.e(ex.getMessage());
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Timber.e(exception.toString());
                }
            });
        } catch (MqttException ex) {
            Timber.e(ex.getMessage());
        }
    }

    private MqttConnectOptions getMQTTOptions() {
        final String mqttUsername = prefs.getString("mqttUsername", "noneMQTTUsername");
        final String mqttPassword = prefs.getString("mqttPassword", "noneMQTTPassword");

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(true);
        mqttConnectOptions.setUserName(mqttUsername);
        mqttConnectOptions.setPassword(mqttPassword.toCharArray());

        return mqttConnectOptions;
    }
}

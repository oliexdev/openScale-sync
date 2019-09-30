/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;

import com.google.gson.Gson;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.gui.view.StatusView;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.Date;

import timber.log.Timber;

public class MQTTSync extends ScaleMeasurementSync {
    private Context context;
    private MqttAndroidClient mqttAndroidClient;
    private String mqttServer;
    private final String clientId = "openScaleSync";

    public MQTTSync(Context context) {
        super(context);
        this.context = context;
        mqttServer = prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199");
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

    @Override
    public void checkStatus(final StatusView statusView) {
        Timber.d("Check MQTT sync status");

        if (!isEnable()) {
            statusView.setCheck(false, "MQTT sync is disabled");
            return;
        }

        if (mqttAndroidClient.isConnected()) {
            Timber.d("already connected to MQTT server, trying to disconnect");

            try {
                mqttAndroidClient.disconnect().setActionCallback(new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        Timber.d("succesful disconnected from MQTT server");
                        checkStatus(statusView);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        statusView.setCheck(false, exception.getMessage());
                    }
                });
            } catch (Exception ex) {
                Timber.e(ex.getMessage());
            }
        } else {
            try {
                Timber.d("Trying to connect to MQTT server");
                mqttServer = prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199");
                mqttAndroidClient = new MqttAndroidClient(context, mqttServer, clientId);

                mqttAndroidClient.connect(getMQTTOptions(), null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        statusView.setCheck(true, "Successful connected to " + mqttServer);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, final Throwable exception) {
                        statusView.setCheck(false, "Failed to connect to " + mqttServer + " " + exception.toString());
                    }
                });
            } catch(MqttException ex){
                statusView.setCheck(false, ex.getMessage());
            }
        }
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
        final String mqttUsername = prefs.getString("mqttUsername", "ztntplvc");
        final String mqttPassword = prefs.getString("mqttPassword", "IqdBs7XMr-Kr");

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();

        try {
            mqttConnectOptions.setAutomaticReconnect(true);
            mqttConnectOptions.setCleanSession(true);
            mqttConnectOptions.setUserName(mqttUsername);
            mqttConnectOptions.setPassword(mqttPassword.toCharArray());
        } catch (Exception e) {
            Timber.e(e.getMessage());
        }

        return mqttConnectOptions;
    }

    public final MqttAndroidClient getMqttAndroidClient() {
        return mqttAndroidClient;
    }
}

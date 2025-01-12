/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;

import com.google.gson.Gson;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.gui.view.StatusView;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.Date;

import info.mqtt.android.service.Ack;
import info.mqtt.android.service.MqttAndroidClient;
import timber.log.Timber;

public class MQTTSync extends ScaleMeasurementSync {
    private Context context;
    private MqttAndroidClient mqttAndroidClient;
    private String mqttServer;
    private final String clientId = "openScaleSync";
    private final String SUBSCRIPTION_TOPIC_SYNC = "measurements/sync";

    public MQTTSync(Context context) {
        super(context);
        this.context = context;
        mqttServer = prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199");
        mqttAndroidClient = new MqttAndroidClient(context, mqttServer, clientId, Ack.AUTO_ACK);
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
                        Timber.d("successful disconnected from MQTT server");
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
                mqttAndroidClient = new MqttAndroidClient(context, mqttServer, clientId, Ack.AUTO_ACK);

                mqttAndroidClient.connect(getMQTTOptions(), null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        statusView.setCheck(true, "Successful connected to " + mqttServer);
                        DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                        disconnectedBufferOptions.setBufferEnabled(true);
                        disconnectedBufferOptions.setBufferSize(100);
                        disconnectedBufferOptions.setPersistBuffer(false);
                        disconnectedBufferOptions.setDeleteOldestMessages(false);
                        mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);

                        subscribeToTopic(SUBSCRIPTION_TOPIC_SYNC);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, final Throwable exception) {
                        statusView.setCheck(false, "Failed to connect to " + mqttServer + " " + exception.toString());
                    }
                });

                mqttAndroidClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean b, String s) {

                    }

                    @Override
                    public void connectionLost(Throwable throwable) {

                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
                        Timber.d("MQTT message received: " + mqttMessage.toString() + " on topic " + topic);

                        if (topic.equals(clientId + "/" + SUBSCRIPTION_TOPIC_SYNC)) {
                            if (mqttMessage.toString().equals("true")) {
                                Timber.d("MQTT sync request received");
                            }
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                });
            } catch(Exception ex){
                statusView.setCheck(false, ex.getMessage());
            }
        }
    }

    private void subscribeToTopic(final String subscriptionTopic) {
        mqttAndroidClient.unsubscribe(clientId + "/" + subscriptionTopic);

        mqttAndroidClient.subscribe(clientId + "/" + subscriptionTopic, 0, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                Timber.d("MQTT subscribe successful to " + clientId + "/" + subscriptionTopic);
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                Timber.d("MQTT subscribe failed to" + clientId + "/" + subscriptionTopic);
            }
        });
    }

    private void sendMessageToMQTT(final String topic, final String payload) {
        final MqttMessage msg = new MqttMessage();
        msg.setPayload(payload.getBytes());
        msg.setQos(2);

        if (mqttAndroidClient.isConnected()) {
            mqttAndroidClient.publish("openScaleSync/" + topic, msg);
        } else{
            mqttAndroidClient.connect(getMQTTOptions(), null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Timber.d("Successful published on " + topic + " with message " + msg);
                    mqttAndroidClient.publish("openScaleSync/" + topic, msg);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Timber.e(exception.toString());
                }
            });
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

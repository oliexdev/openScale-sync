/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync

import android.content.Context
import androidx.activity.ComponentActivity
import com.google.gson.Gson
import com.health.openscale.sync.core.datatypes.ScaleMeasurement
import com.health.openscale.sync.gui.view.StatusViewAdapter
import info.mqtt.android.service.Ack
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import timber.log.Timber
import java.util.Date

class MQTTSync(private val context: Context) : ScaleMeasurementSync(context) {
    var mqttAndroidClient: MqttAndroidClient
        private set
    private var mqttServer: String?
    private val clientId = "openScaleSync"
    private val SUBSCRIPTION_TOPIC_SYNC = "measurements/sync"

    init {
        mqttServer = prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199")
        mqttAndroidClient = MqttAndroidClient(context, mqttServer!!, clientId, Ack.AUTO_ACK)
    }

    override fun getName(): String {
        return "MQTTSync"
    }

    override fun isEnable(): Boolean {
        return prefs.getBoolean("enableMQTT", false)
    }

    override suspend fun insert(measurement: ScaleMeasurement) {
        val gson = Gson()
        val jsonMeasurement = gson.toJson(measurement)

        sendMessageToMQTT("measurements/insert", jsonMeasurement)
    }

    override fun delete(date: Date) {
        sendMessageToMQTT("measurements/delete", "{\"date\":" + date.time + "}")
    }

    override fun clear() {
        sendMessageToMQTT("measurements/clear", "true")
    }

    override fun update(measurement: ScaleMeasurement) {
        val gson = Gson()
        val jsonMeasurement = gson.toJson(measurement)

        sendMessageToMQTT("measurements/update", jsonMeasurement)
    }

    override fun hasPermission(): Boolean {

        return false
    }

    override fun askPermission(context: ComponentActivity) {

    }

    override fun checkStatus(statusView: StatusViewAdapter) {
        Timber.d("Check MQTT sync status")

        if (!isEnable()) {
            return
        }

        if (mqttAndroidClient.isConnected) {
            Timber.d("already connected to MQTT server, trying to disconnect")

            try {
                mqttAndroidClient.disconnect().actionCallback = object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken) {
                        Timber.d("successful disconnected from MQTT server")
                        checkStatus(statusView)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex.message)
            }
        } else {
            try {
                Timber.d("Trying to connect to MQTT server")
                mqttServer = prefs.getString("mqttServer", "tcp://farmer.cloudmqtt.com:16199")
                mqttAndroidClient = MqttAndroidClient(context, mqttServer!!, clientId, Ack.AUTO_ACK)

                mqttAndroidClient.connect(mQTTOptions, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken) {
                        val disconnectedBufferOptions = DisconnectedBufferOptions()
                        disconnectedBufferOptions.isBufferEnabled = true
                        disconnectedBufferOptions.bufferSize = 100
                        disconnectedBufferOptions.isPersistBuffer = false
                        disconnectedBufferOptions.isDeleteOldestMessages = false
                        mqttAndroidClient.setBufferOpts(disconnectedBufferOptions)

                        subscribeToTopic(SUBSCRIPTION_TOPIC_SYNC)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    }
                })

                mqttAndroidClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(b: Boolean, s: String) {
                    }

                    override fun connectionLost(throwable: Throwable) {
                    }

                    @Throws(Exception::class)
                    override fun messageArrived(topic: String, mqttMessage: MqttMessage) {
                        Timber.d("MQTT message received: $mqttMessage on topic $topic")

                        if (topic == "$clientId/$SUBSCRIPTION_TOPIC_SYNC") {
                            if (mqttMessage.toString() == "true") {
                                Timber.d("MQTT sync request received")
                            }
                        }
                    }

                    override fun deliveryComplete(iMqttDeliveryToken: IMqttDeliveryToken) {
                    }
                })
            } catch (ex: Exception) {
            }
        }
    }

    private fun subscribeToTopic(subscriptionTopic: String) {
        mqttAndroidClient.unsubscribe("$clientId/$subscriptionTopic")

        mqttAndroidClient.subscribe(
            "$clientId/$subscriptionTopic",
            0,
            null,
            object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    Timber.d("MQTT subscribe successful to $clientId/$subscriptionTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    Timber.d("MQTT subscribe failed to$clientId/$subscriptionTopic")
                }
            })
    }

    private fun sendMessageToMQTT(topic: String, payload: String) {
        val msg = MqttMessage()
        msg.payload = payload.toByteArray()
        msg.qos = 2

        if (mqttAndroidClient.isConnected) {
            mqttAndroidClient.publish("openScaleSync/$topic", msg)
        } else {
            mqttAndroidClient.connect(mQTTOptions, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    Timber.d("Successful published on $topic with message $msg")
                    mqttAndroidClient.publish("openScaleSync/$topic", msg)
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    Timber.e(exception.toString())
                }
            })
        }
    }

    private val mQTTOptions: MqttConnectOptions
        get() {
            val mqttUsername = prefs.getString("mqttUsername", "ztntplvc")
            val mqttPassword = prefs.getString("mqttPassword", "IqdBs7XMr-Kr")

            val mqttConnectOptions = MqttConnectOptions()

            try {
                mqttConnectOptions.isAutomaticReconnect = true
                mqttConnectOptions.isCleanSession = true
                mqttConnectOptions.userName = mqttUsername
                mqttConnectOptions.password = mqttPassword!!.toCharArray()
            } catch (e: Exception) {
                Timber.e(e.message)
            }

            return mqttConnectOptions
        }
}

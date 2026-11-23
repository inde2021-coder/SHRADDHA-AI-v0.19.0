package `in`.shraddha.ai.brain

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** TLS-only MQTT bridge. It publishes only to explicitly approved Home Assistant command topics. */
class MqttHomeAssistantBridge(context: Context) {
    data class Config(val brokerUrl: String, val username: String, val password: String, val baseTopic: String)
    private val store = EncryptedFeatureStore(context.applicationContext)
    private val clientRef = AtomicReference<MqttAsyncClient?>(null)

    fun saveConfig(brokerUrl: String, username: String, password: String, baseTopic: String): Result<Unit> = runCatching {
        val uri = URI(brokerUrl.trim())
        require(uri.scheme.equals("ssl", true) && !uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "MQTT broker must use TLS: ssl://hostname:8883"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrBlank()) {
            "MQTT broker URL cannot contain credentials, paths, query or fragments."
        }
        require(username.length <= 128 && password.length in 8..512) { "MQTT credentials are invalid." }
        val topic = normalizeBaseTopic(baseTopic)
        store.put(CONFIG_KEY, JSONObject().put("brokerUrl", brokerUrl.trim()).put("username", username)
            .put("password", password).put("baseTopic", topic).toString()).getOrThrow()
    }

    fun loadConfig(): Result<Config?> = runCatching {
        val raw = store.get(CONFIG_KEY).getOrThrow()
        if (raw.isBlank()) null else JSONObject(raw).let {
            Config(it.getString("brokerUrl"), it.getString("username"), it.getString("password"), normalizeBaseTopic(it.getString("baseTopic")))
        }
    }

    fun connect(onStatus: (String) -> Unit): Result<Unit> = runCatching {
        val config = loadConfig().getOrThrow() ?: error("Save MQTT broker settings first.")
        disconnect()
        val client = MqttAsyncClient(config.brokerUrl, "shraddha-${UUID.randomUUID()}", MemoryPersistence())
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                onStatus(if (reconnect) "MQTT TLS reconnected." else "MQTT TLS connected.")
            }
            override fun connectionLost(cause: Throwable?) { onStatus("MQTT disconnected: ${cause?.message ?: "connection lost"}") }
            override fun messageArrived(topic: String, message: MqttMessage) {
                onStatus("MQTT update received on ${topic.take(100)} (${message.payload.size} bytes).")
            }
            override fun deliveryComplete(token: IMqttToken?) { onStatus("MQTT command delivery acknowledged by broker.") }
        })
        clientRef.set(client)
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 30
            userName = config.username
            password = config.password.toCharArray()
            isHttpsHostnameVerificationEnabled = true
        }
        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) { onStatus("MQTT TLS connection established.") }
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { onStatus("MQTT connection failed: ${exception?.message ?: "unknown error"}") }
        })
    }

    fun subscribeStateTopics(onStatus: (String) -> Unit): Result<Unit> = runCatching {
        val config = loadConfig().getOrThrow() ?: error("Save MQTT broker settings first.")
        val client = clientRef.get() ?: error("Connect to the MQTT broker first.")
        require(client.isConnected) { "MQTT broker is not connected." }
        client.subscribe("${config.baseTopic}/+/state", 1, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) { onStatus("Subscribed to approved device state topics.") }
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { onStatus("MQTT subscription failed: ${exception?.message}") }
        })
    }

    fun publishCommand(deviceId: String, command: String, confirmed: Boolean, onStatus: (String) -> Unit): Result<Unit> = runCatching {
        require(confirmed) { "Explicit user confirmation is required before publishing a device command." }
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid MQTT device ID." }
        require(command in setOf("ON", "OFF", "TOGGLE")) { "MQTT command is not allowlisted." }
        val config = loadConfig().getOrThrow() ?: error("Save MQTT broker settings first.")
        val client = clientRef.get() ?: error("Connect to the MQTT broker first.")
        require(client.isConnected) { "MQTT broker is not connected." }
        val topic = "${config.baseTopic}/$deviceId/set"
        client.publish(topic, MqttMessage(command.toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = false },
            null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { onStatus("MQTT broker acknowledged command publication to $topic; device execution is not yet confirmed.") }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { onStatus("MQTT publish failed: ${exception?.message}") }
            })
    }

    fun disconnect() { runCatching { clientRef.getAndSet(null)?.disconnectForcibly() } }
    fun clearConfig(): Result<Unit> = runCatching { disconnect(); check(store.remove(CONFIG_KEY)) { "Could not clear MQTT settings." } }

    private fun normalizeBaseTopic(value: String): String {
        val topic = value.trim().trim('/')
        require(topic.matches(Regex("[A-Za-z0-9_/-]{1,120}")) && !topic.contains("//")) { "MQTT base topic is invalid." }
        require(topic.split('/').none { it == "+" || it == "#" || it == "." || it == ".." }) { "MQTT base topic cannot contain wildcards or traversal segments." }
        return topic
    }

    companion object { private const val CONFIG_KEY = "mqtt_home_assistant_config_v1" }
}

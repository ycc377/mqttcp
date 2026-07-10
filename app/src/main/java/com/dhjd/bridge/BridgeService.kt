package com.dhjd.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class BridgeService : Service() {

    companion object {
        const val TAG = "DHJDBridge"
        const val MQTT_HOST = "47.108.201.203"
        const val MQTT_PORT = 1883
        const val MQTT_USER = "PLC"
        const val MQTT_PASS = "ycc377"
        const val CHANNEL_ID = "dhjd_bridge_channel"
        const val NOTIFY_ID = 1001
    }

    private var mqttClient: MqttClient? = null
    private var deviceID = ""
    private val tunnels = ConcurrentHashMap<String, TunnelState>()
    private var isRunning = false

    data class TunnelState(
        var configName: String,
        var peerID: String,
        var remoteIP: String,
        var remotePort: Int,
        var socket: Socket? = null,
        var readThread: Thread? = null
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        deviceID = generateID()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification("正在连接服务器..."))
        connectMQTT()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 通知 ----
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DHJD桥接服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DHJD桥接")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotify(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(text))
    }

    // ---- ID 生成 ----
    private fun generateID(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val mac = intf.hardwareAddress ?: continue
                if (mac.isNotEmpty()) {
                    val md5 = MessageDigest.getInstance("MD5").digest(mac)
                    var n = (md5[0].toInt() and 0xFF)
                    n = n or ((md5[1].toInt() and 0xFF) shl 8)
                    n = n or ((md5[2].toInt() and 0xFF) shl 16)
                    n = n or ((md5[3].toInt() and 0xFF) shl 24)
                    if (n < 0) n = -n
                    return String.format("%09d", n % 1000000000)
                }
            }
        } catch (_: Exception) {}
        return "000000001"
    }

    // ---- MQTT ----
    private fun connectMQTT() {
        thread {
            try {
                val clientId = "dhjd-android-${deviceID}-${System.currentTimeMillis()}"
                val opt = MqttConnectOptions().apply {
                    userName = MQTT_USER
                    password = MQTT_PASS.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    willDestination = "${deviceID}/online"
                    willPayload = "0".toByteArray()
                    isAutomaticReconnect = true
                }

                mqttClient = MqttClient(
                    "tcp://$MQTT_HOST:$MQTT_PORT",
                    clientId, MemoryPersistence()
                )
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "MQTT断开: ${cause?.message}")
                        updateNotify("服务器断开")
                    }

                    @Throws(Exception::class)
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        handleMessage(topic, String(message.payload))
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(opt)
                mqttClient?.subscribe("cmd/$deviceID", 0)
                mqttClient?.publish("${deviceID}/online", "1".toByteArray(), 0, true)
                updateNotify("服务器已连接 · $deviceID")
                Log.i(TAG, "MQTT已连接, ID=$deviceID")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT连接失败: ${e.message}")
                updateNotify("连接失败，10秒后重试")
                Thread.sleep(10000)
                connectMQTT()
            }
        }
    }

    // ---- 指令处理 ----
    private fun handleMessage(topic: String, payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val cmd = json.optString("cmd")
            when (cmd) {
                "connect" -> {
                    val from = json.optString("from")
                    val remoteIP = json.optString("remote_ip")
                    val remotePort = json.optInt("remote_port")
                    val channel = json.optInt("channel")
                    Log.i(TAG, "连接指令: $from -> $remoteIP:$remotePort 通道=$channel")
                    doConnect(from, remoteIP, remotePort, channel)
                }
                "disconnect" -> {
                    val from = json.optString("from")
                    Log.i(TAG, "断开指令: $from")
                    disconnectPeer(from)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息解析失败: ${e.message}")
        }
    }

    // ---- 被控连PLC ----
    private fun doConnect(peerID: String, remoteIP: String, remotePort: Int, channel: Int) {
        thread {
            val key = "从-$peerID-$remoteIP-$remotePort-$channel"

            // 清理旧连接
            tunnels.remove(key)?.let { old ->
                try { old.socket?.close() } catch (_: Exception) {}
            }

            try {
                val sock = Socket().apply {
                    connect(InetSocketAddress(remoteIP, remotePort), 5000)
                    soTimeout = 0
                }

                val state = TunnelState(
                    configName = key,
                    peerID = peerID,
                    remoteIP = remoteIP,
                    remotePort = remotePort,
                    socket = sock
                )
                tunnels[key] = state

                // 订阅 tx 主题（收主控发来的SCADA数据），用通道号
                mqttClient?.subscribe("tx/$peerID/$channel", 0)

                // 报 ok
                reply(peerID, "port_allocated", "ok", "channel" to channel.toString())
                updateNotify("已连接 $peerID 通道=$channel")
                Log.i(TAG, "已连接PLC $remoteIP:$remotePort 通道=$channel")

                // 启动读线程（PLC→主控）
                state.readThread = thread {
                    try {
                        val buf = ByteArray(65536)
                        val input: InputStream = sock.getInputStream()
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            // 发到 rx/{masterID}/{channel}
                            mqttClient?.publish(
                                "rx/$peerID/$channel",
                                buf.copyOfRange(0, n), 0, false
                            )
                        }
                    } catch (_: Exception) {}
                    finally {
                        cleanupTunnel(key)
                        reply(peerID, "port_allocated", "disconnected")
                        updateNotify("$peerID 已断开")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "连接PLC失败 $remoteIP:$remotePort: ${e.message}")
                reply(peerID, "port_allocated", "fail", "msg" to (e.message ?: "未知错误"))
            }
        }
    }

    private fun disconnectPeer(peerID: String) {
        val toRemove = tunnels.filter { it.value.peerID == peerID }
        toRemove.keys.forEach { key ->
            cleanupTunnel(key)
        }
    }

    private fun cleanupTunnel(key: String) {
        tunnels.remove(key)?.let { t ->
            try { t.socket?.close() } catch (_: Exception) {}
            try { mqttClient?.unsubscribe("tx/${t.peerID}/+") } catch (_: Exception) {}
        }
    }

    private fun reply(to: String, cmd: String, status: String, vararg extra: Pair<String, String>) {
        try {
            val json = org.json.JSONObject().apply {
                put("to", to)
                put("cmd", cmd)
                put("status", status)
                extra.forEach { put(it.first, it.second) }
            }
            mqttClient?.publish("data/$deviceID", json.toString().toByteArray(), 0, false)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        tunnels.keys.forEach { cleanupTunnel(it) }
        try {
            mqttClient?.publish("${deviceID}/online", "0".toByteArray(), 0, true)
            mqttClient?.disconnect()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

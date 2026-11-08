package io.github.novelreader

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter bridge for the direct GT2 Bluetooth route.
 *
 * The phone talks to the watch over BR/EDR RFCOMM and the private GT2 file
 * service. There is deliberately no Health/WearKit/Wear Engine dependency in
 * this APK, so the route does not require a partner whitelist or AGC scope.
 */
class MainActivity : FlutterActivity() {
    private val methodChannelName = "novel_reader/direct_bluetooth"
    private val eventChannelName = "novel_reader/direct_bluetooth_events"
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result -> handleMethod(call, result) }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) { eventSink = sink }
                override fun onCancel(arguments: Any?) { eventSink = null }
            })
    }

    private fun handleMethod(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isDirectBluetoothAvailable" -> {
                val adapter = bluetoothAdapter()
                result.success(adapter != null && adapter.isEnabled)
            }
            "listBluetoothDevices" -> result.success(listBondedDevices())
            "sendFile" -> {
                val args = call.arguments as? Map<*, *>
                val path = args?.get("path") as? String
                val mac = args?.get("mac") as? String
                if (path.isNullOrBlank() || mac.isNullOrBlank()) {
                    result.error("ARGUMENT", "path and mac are required", null)
                    return
                }
                if (!hasBluetoothPermission()) {
                    requestBluetoothPermission()
                    result.error("PERMISSION", "请允许蓝牙连接权限后重试", null)
                    return
                }
                val adapter = bluetoothAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    result.error("BLUETOOTH", "请先打开蓝牙并在系统设置中配对 GT2", null)
                    return
                }
                val file = File(path)
                if (!file.isFile) {
                    result.error("FILE", "File does not exist: $path", null)
                    return
                }
                Thread {
                    try {
                        Gt2DirectClient(this, adapter) { value ->
                            postStatus("progress", mapOf("value" to value))
                        }.sendNovel(mac.trim(), file)
                        success(result, mapOf("route" to "direct-rfcomm", "fileType" to 7))
                    } catch (error: Throwable) {
                        fail(result, "SEND", error.message ?: error.javaClass.simpleName)
                    }
                }.start()
            }
            else -> result.notImplemented()
        }
    }

    @Suppress("DEPRECATION")
    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, 7401)
    }

    @Suppress("MissingPermission")
    private fun listBondedDevices(): List<Map<String, String>> {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission()
            return emptyList()
        }
        return bluetoothAdapter()?.bondedDevices.orEmpty().map {
            mapOf("name" to (it.name ?: "GT2"), "address" to it.address)
        }.sortedBy { it["name"] }
    }

    private fun success(result: MethodChannel.Result, value: Any?) =
        mainHandler.post { result.success(value) }

    private fun fail(result: MethodChannel.Result, code: String, message: String) =
        mainHandler.post { result.error(code, message, null) }

    private fun postStatus(type: String, values: Map<String, Any?>) =
        mainHandler.post { eventSink?.success(mapOf("type" to type) + values) }
}

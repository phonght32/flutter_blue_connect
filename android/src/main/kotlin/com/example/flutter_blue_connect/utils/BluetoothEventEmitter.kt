package com.example.flutter_blue_connect

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.EventChannel

object BluetoothEventEmitter {
    var eventSink: EventChannel.EventSink? = null

    fun emit(
        layer: String,
        event: String,
        bluetoothAddress: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        val payload = mapOf(
            "layer" to layer,
            "event" to event,
            "bluetoothAddress" to bluetoothAddress,
            "deviceInfo" to FlutterBlueDeviceManager.getMap()
        ) + data

        FlutterBlueLog.info("Emit event {layer=$layer, event=$event, address=$bluetoothAddress}")

        Handler(Looper.getMainLooper()).post {
            eventSink?.success(payload)
        }
    }
}

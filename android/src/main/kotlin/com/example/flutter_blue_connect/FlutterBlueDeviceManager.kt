package com.example.flutter_blue_connect

import com.example.flutter_blue_connect.models.FlutterBlueDevice

object FlutterBlueDeviceManager {

    var connectedDevice: FlutterBlueDevice? = null

    fun updateDevice(
        name: String? = null,
        address: String? = null,
        rssi: Int? = null,
        linkLayerState: String? = null,
        l2capState: String? = null,
        bondState: String? = null,
        encryptionState: String? = null
    ) {
        if (connectedDevice == null) {
            connectedDevice = FlutterBlueDevice()
        }

        name?.let { connectedDevice?.name = it }
        address?.let { connectedDevice?.bluetoothAddress = it }
        rssi?.let { connectedDevice?.rssi = it }
        linkLayerState?.let { connectedDevice?.linkLayerState = it }
        l2capState?.let { connectedDevice?.l2capState = it }
        bondState?.let { connectedDevice?.bondState = it }
        encryptionState?.let { connectedDevice?.encryptionState = it }
    }

    fun clear() {
        connectedDevice = null
    }

    fun getMap(): Map<String, Any?>? {
        return connectedDevice?.toMap()
    }
}

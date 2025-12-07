package com.example.flutter_blue_connect

object FlutterBlueDeviceManager {

    data class FlutterBlueDevice(
        var name: String = "Unnamed",
        var bluetoothAddress: String = "",
        var advData: List<Int> = emptyList(), // ✅ default empty list
        var rssi: Int? = null,
        var linkLayerState: String? = null,
        var l2capState: String? = null,
        var bondState: String? = null,
        var encryptionState: String? = null,
    ) {
        fun toMap(): Map<String, Any?> {
            return mapOf(
                "name" to name,
                "bluetoothAddress" to bluetoothAddress,
                "advData" to ArrayList(advData), // ✅ this goes to Dart as List<Int>
                "rssi" to rssi,
                "linkLayerState" to linkLayerState,
                "l2capState" to l2capState,
                "bondState" to bondState,
                "encryptionState" to encryptionState
            )
        }
    }

    var connectedDevice: FlutterBlueDevice? = null

    fun updateDevice(
        name: String? = null,
        address: String? = null,
        advData: List<Int>? = null,
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
        advData?.let { connectedDevice?.advData = it }
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

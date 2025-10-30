package com.example.flutter_blue_connect.models

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

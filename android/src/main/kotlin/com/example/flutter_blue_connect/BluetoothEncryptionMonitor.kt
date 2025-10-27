package com.example.flutter_blue_connect

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object BluetoothEncryptionMonitor {

  private val handler = Handler(Looper.getMainLooper())
  private var lastEncryptionState: String? = null

  // Runs periodically
  private val encryptionCheckRunnable = object : Runnable {
    override fun run() {
      try {
        // Get the current connected device info safely
        val deviceInfo = FlutterBlueDeviceManager.getMap()

        if (deviceInfo != null) {
          val address = deviceInfo["bluetoothAddress"] as? String ?: ""

          if (address.isNotEmpty()) {
            val gatt = FlutterBlueConnectPlugin.activeGattConnections[address]

            if (gatt != null) {
              val device = gatt.device
              val isEncrypted = isConnectionEncrypted(device)
              val newState = if (isEncrypted) "encrypted" else "notEncrypted"

              if (lastEncryptionState == null || lastEncryptionState != newState) {
                lastEncryptionState = newState

                FlutterBlueDeviceManager.updateDevice(encryptionState = newState)

                BluetoothEventEmitter.emit(
                  layer = "gap",
                  event = "encryptionStateChanged",
                  bluetoothAddress = address,
                )

                Log.d(
                  "BluetoothEncryptionMonitor",
                  "🔒 Encryption state changed: $address → $newState"
                )
              }
            }
          }
        }
      } catch (e: Exception) {
        Log.w("BluetoothEncryptionMonitor", "Check failed: ${e.message}")
      }

      handler.postDelayed(this, 1000) // check every 5s
    }
  }

  fun start() {
    handler.post(encryptionCheckRunnable)
    Log.d("BluetoothEncryptionMonitor", "Started periodic encryption check")
  }

  fun stop() {
    handler.removeCallbacks(encryptionCheckRunnable)
    Log.d("BluetoothEncryptionMonitor", "Stopped periodic encryption check")
  }

  // Uses reflection to check if the connection is encrypted
  private fun isConnectionEncrypted(device: android.bluetooth.BluetoothDevice): Boolean {
    return try {
      val method = device.javaClass.getMethod("isEncrypted")
      method.invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
      false
    }
  }
}

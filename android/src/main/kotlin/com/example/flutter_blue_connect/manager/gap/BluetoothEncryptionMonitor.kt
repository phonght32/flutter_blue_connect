package com.example.flutter_blue_connect

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object BluetoothEncryptionMonitor {

  private val handler = Handler(Looper.getMainLooper())
  private var lastEncryptionState: String? = "notEncrypted"

  // Runs periodically
  private val encryptionCheckRunnable = object : Runnable {
    override fun run() {
      try {
        // Get the current connected device info safely
        val deviceInfo = FlutterBlueDeviceManager.getMap()

        if (deviceInfo != null) {
          val address = deviceInfo["bluetoothAddress"] as? String ?: ""

          if (address.isNotEmpty()) {
            val gatt = FlutterBlueGapManager.activeGattConnections[address]

            if (gatt != null) {
              val device = gatt.device
              val isEncrypted = isConnectionEncrypted(device)
              val newState = if (isEncrypted) "encrypted" else "notEncrypted"

              if (lastEncryptionState != newState) {
                lastEncryptionState = newState

                FlutterBlueDeviceManager.updateDevice(encryptionState = newState)

                BluetoothEventEmitter.emit("gap", "encryptionStateChanged", address)
              }
            }
          }
        }
      } catch (e: Exception) {
        FlutterBlueLog.error("Monitor encyption failed, message: ${e.message}")
      }

      handler.postDelayed(this, 100) // check every 100ms
    }
  }

  fun start() {
    handler.post(encryptionCheckRunnable)
  }

  fun stop() {
    handler.removeCallbacks(encryptionCheckRunnable)
  }

  fun resetState() {
    lastEncryptionState = "notEncrypted"
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

package com.example.flutter_blue_connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothSocket

import android.os.Build
import android.content.Context

import io.flutter.embedding.engine.plugins.FlutterPlugin

object FlutterBlueSmpManager {
  // Bluetooth adapter reference
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothManager: BluetoothManager? = null

  fun startPairing(bluetoothAddress: String) {
    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      FlutterBlueLog.error("Device not found for address $bluetoothAddress")
      return
    }

    try {
      val success = device.createBond()
      if (success) {
        FlutterBlueLog.info("Pairing initiated with $bluetoothAddress")
      } else {
        FlutterBlueLog.error("createBond() returned false")
      }
    } catch (e: Exception) {
      FlutterBlueLog.error("PAIRING_ERROR ${e.message}")
    }
  }

  fun startPairingOob(bluetoothAddress: String, oobData: ByteArray) {
    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      FlutterBlueLog.error("Device not found for address $bluetoothAddress")
      return
    }

    try {
      // Check Android version for OOB support
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {

        // For Android 10 (Q) and above - OOB pairing with data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && oobData != null) {
          FlutterBlueLog.info("Starting OOB pairing with data for $bluetoothAddress")

          // Parse OOB data - typically contains confirmation value and randomizer
          // Format depends on your OOB exchange mechanism
          if (oobData.size >= 32) {
            val confirmationValue = oobData.copyOfRange(0, 16)
            val randomizer = oobData.copyOfRange(16, 32)

            // Use reflection to access setOobData method
            val method = device.javaClass.getMethod(
              "setOobData",
              ByteArray::class.java,
              ByteArray::class.java
            )
            method.invoke(device, confirmationValue, randomizer)

            FlutterBlueLog.info("OOB data set successfully")
          } else {
            FlutterBlueLog.error("OOB data must be at least 32 bytes")
            return
          }
        }

        // Initiate the bonding process
        val success = device.createBond()

        if (success) {
          FlutterBlueLog.info("OOB pairing initiated with $bluetoothAddress")
        } else {
          FlutterBlueLog.error("createBond() returned false for $bluetoothAddress")
        }

      } else {
        FlutterBlueLog.error("OOB pairing requires Android 4.4 (KitKat) or higher")
      }

    } catch (e: NoSuchMethodException) {
      FlutterBlueLog.error("OOB method not available: ${e.message}")
    } catch (e: Exception) {
      FlutterBlueLog.error("OOB pairing error: ${e.message}")
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    bluetoothManager = binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun onDetachedFromEngine() {

  }
}
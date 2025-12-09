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

import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.embedding.engine.plugins.FlutterPlugin

object FlutterBlueSmpManager {
  // Bluetooth adapter reference
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothManager: BluetoothManager? = null

  fun startPairing(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    if (bluetoothAddress == null) {
      FlutterBlueLog.error("Missing bluetooth address")
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      FlutterBlueLog.error("Device not found for address $bluetoothAddress")
      result.error("DEVICE_NOT_FOUND", "Device not found for $bluetoothAddress", null)
      return
    }

    try {
      val success = device.createBond()
      if (success) {
        FlutterBlueLog.info("Pairing initiated with $bluetoothAddress")
        result.success(true)
      } else {
        FlutterBlueLog.error("createBond() returned false")
        result.error("PAIRING_FAILED", "createBond() returned false", null)
      }
    } catch (e: Exception) {
      FlutterBlueLog.error("PAIRING_ERROR ${e.message}")
      result.error("PAIRING_ERROR", e.message, null)
    }
  }

  fun startPairingOob(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val oobData = call.argument<ByteArray>("oobData")

    if (bluetoothAddress == null) {
      FlutterBlueLog.error("Missing bluetooth address")
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    if (oobData == null) {
      FlutterBlueLog.error("Missing oob data")
      result.error("INVALID_ARGUMENT", "Missing oob data.", null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      FlutterBlueLog.error("Device not found for address $bluetoothAddress")
      result.error("DEVICE_NOT_FOUND", "Device not found for $bluetoothAddress", null)
      return
    }

    try {
      // Android >= 4.4
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
        result.error("UNSUPPORTED", "OOB pairing requires Android 4.4 or higher", null)
        return
      }

      // For Android 10+ with OOB data
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (oobData.size < 32) {
          FlutterBlueLog.error("OOB data must be >= 32 bytes")
          result.error("INVALID_OOB", "OOB data must be at least 32 bytes", null)
          return
        }

        val confirmationValue = oobData.copyOfRange(0, 16)
        val randomizer = oobData.copyOfRange(16, 32)

        val method = device.javaClass.getMethod(
          "setOobData",
          ByteArray::class.java,
          ByteArray::class.java
        )
        method.invoke(device, confirmationValue, randomizer)

        FlutterBlueLog.info("OOB data set successfully")
      }

      // Start bonding
      val success = device.createBond()
      if (success) {
        FlutterBlueLog.info("OOB pairing initiated with $bluetoothAddress")
        result.success(true)
      } else {
        FlutterBlueLog.error("createBond() returned false for $bluetoothAddress")
        result.error("PAIRING_FAILED", "createBond() returned false", null)
      }

    } catch (e: NoSuchMethodException) {
      FlutterBlueLog.error("OOB method not available: ${e.message}")
      result.error("OOB_NOT_SUPPORTED", "setOobData not available", null)
    } catch (e: Exception) {
      FlutterBlueLog.error("OOB pairing error: ${e.message}")
      result.error("OOB_ERROR", e.message, null)
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    bluetoothManager = binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun onDetachedFromEngine() {

  }
}
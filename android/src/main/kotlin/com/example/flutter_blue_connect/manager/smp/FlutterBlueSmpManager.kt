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
      val errorMsg = "Start pairing failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      val errorMsg = "Start pairing failed, reason: device not exist"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
      return
    }

    try {
      val success = device.createBond()
      if (success) {
        FlutterBlueLog.info("Pairing initiated with $bluetoothAddress")
        result.success(true)
      } else {
        val errorMsg = "Start pairing failed, reason: createBond() returned false"
        FlutterBlueLog.error(errorMsg)
        result.error("PAIRING_FAILED", errorMsg, null)
      }
    } catch (e: Exception) {
      val errorMsg = "Start pairing failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("PAIRING_FAILED", errorMsg, null)
    }
  }

  fun startPairingOob(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val oobData = call.argument<ByteArray>("oobData")

    if (bluetoothAddress == null) {
      val errorMsg = "Start pairing OOB failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    if (oobData == null) {
      val errorMsg = "Start pairing OOB failed, reason: missing oob data"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      val errorMsg = "Start pairing OOB failed, reason: device not exist"
      FlutterBlueLog.error(errorMsg)
      result.error("DEVICE_NOT_FOUND", errorMsg, null)
      return
    }

    try {
      // Android >= 4.4
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
        val errorMsg = "Start pairing OOB failed, reason: OOB pairing requires Android 4.4 or higher"
        FlutterBlueLog.error(errorMsg)
        result.error("UNSUPPORTED", errorMsg, null)
        return
      }

      // For Android 10+ with OOB data
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (oobData.size < 32) {
          val errorMsg = "Start pairing OOB failed, reason: OOB data must be at least 32 bytes"
          FlutterBlueLog.error(errorMsg)
          result.error("INVALID_OOB", errorMsg, null)
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
        val errorMsg = "Start pairing OOB failed, reason: createBond() returned false"
        FlutterBlueLog.error(errorMsg)
        result.error("INVALID_OOB", errorMsg, null)
      }

    } catch (e: NoSuchMethodException) {
      val errorMsg = "Start pairing OOB failed, reason: setOobData not available, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("OOB_NOT_SUPPORTED", errorMsg, null)
    } catch (e: Exception) {
      val errorMsg = "Start pairing OOB failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("OOB_ERROR", errorMsg, null)
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    bluetoothManager = binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun onDetachedFromEngine() {

  }
}
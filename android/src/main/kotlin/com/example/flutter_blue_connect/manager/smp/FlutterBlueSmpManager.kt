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

  private fun generateLocalLeScOobData() {
//        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
//        if (bluetoothAdapter == null) {
//          result.error("NO_ADAPTER", "BluetoothAdapter not available", null)
//          return
//        }
//
//        try {
//          // Find the hidden method via reflection
//          val method = bluetoothAdapter.javaClass.declaredMethods.firstOrNull {
//            it.name == "generateLocalOobData"
//          }
//
//          if (method == null) {
//            result.error("METHOD_NOT_FOUND", "generateLocalOobData not available", null)
//            return
//          }
//
//          // Load hidden classes reflectively
//          val callbackClass = Class.forName("android.bluetooth.BluetoothAdapter\$OobDataCallback")
//          val oobDataClass = Class.forName("android.bluetooth.OobData")
//
//          // Build dynamic proxy for the callback
//          val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
//            callbackClass.classLoader,
//            arrayOf(callbackClass)
//          ) { _, method, args ->
//            if (method.name == "onOobData") {
//              val arg = args?.getOrNull(0)
//              Log.d("FlutterBlueConnect", "OOB callback arg type: ${arg?.javaClass?.name}")
//
//              if (oobDataClass.isInstance(arg)) {
//                val oobData = arg
//
//                fun getBytes(fn: String): ByteArray? = try {
//                  oobDataClass.getMethod(fn).invoke(oobData) as? ByteArray
//                } catch (e: Exception) {
//                  Log.e("FlutterBlueConnect", "Error calling $fn: ${e.message}")
//                  null
//                }
//
//                val map = mapOf(
//                  "confirmationHash" to (getBytes("getConfirmationHash")?.joinToString(",") ?: ""),
//                  "randomizerHash" to (getBytes("getRandomizerHash")?.joinToString(",") ?: ""),
//                  "deviceAddressWithType" to (getBytes("getDeviceAddressWithType")?.joinToString(",") ?: ""),
//                  "leTemporaryKey" to (getBytes("getLeTemporaryKey")?.joinToString(",") ?: "")
//                )
//
//                Handler(Looper.getMainLooper()).post {
//                  Log.d("FlutterBlueConnect", "✅ OOB DATA GENERATED: $map")
//                  result.success(map)
//                }
//              } else {
//                Log.w("FlutterBlueConnect", "OOB callback returned unexpected type: ${arg?.javaClass}")
//                Handler(Looper.getMainLooper()).post {
//                  result.error("NULL_OOB", "OOB callback returned invalid type", null)
//                }
//              }
//            }
//            null
//          }
//
//          // Inline Executor proxy
//          val executorInterface = Class.forName("java.util.concurrent.Executor")
//          val executorProxy = java.lang.reflect.Proxy.newProxyInstance(
//            executorInterface.classLoader,
//            arrayOf(executorInterface)
//          ) { _, m, args ->
//            if (m.name == "execute") {
//              val runnable = args?.getOrNull(0) as? Runnable
//              runnable?.run()
//            }
//            null
//          }
//
//          // TRANSPORT_LE = 2
//          method.invoke(bluetoothAdapter, 2, executorProxy, callbackProxy)
//
//        } catch (e: Exception) {
//          result.error("OOB_ERROR", e.toString(), null)
//        }
  }

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
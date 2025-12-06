package com.example.flutter_blue_connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings

import android.ranging.RangingManager;

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder

import android.annotation.SuppressLint
import android.util.Log

import androidx.annotation.RequiresApi

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

import kotlin.experimental.xor





import com.example.flutter_blue_connect.FlutterBlueConnectPlugin



/**
 * Flutter plugin to manage Bluetooth Low Energy (BLE) scanning in Android.
 * Communicates with Flutter using MethodChannel for commands and EventChannel for real-time updates.
 */
class FlutterBlueConnectPlugin: FlutterPlugin, MethodChannel.MethodCallHandler {

  // Channels for communication with Flutter
  private lateinit var methodChannel: MethodChannel
  private lateinit var bluetoothEventChannel: EventChannel
  private lateinit var appContext: Context

  // Bluetooth adapter reference
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothManager: BluetoothManager? = null



  // Sink for sending scan results to Flutter
  private var bluetoothEventSink: EventChannel.EventSink? = null






  private var generatedOobData: Map<String, Any>? = null



  private var channelSoundingManager: ChannelSoundingManager? = null


  private fun logMessage(level: String, message: String) {
    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
      .format(java.util.Date())
    val logMsg = "[$ts] $message"

    when (level.lowercase()) {
      "info" -> Log.i("FlutterBlueConnect", logMsg)
      "warn", "warning" -> Log.w("FlutterBlueConnect", logMsg)
      "error" -> Log.e("FlutterBlueConnect", logMsg)
      "debug" -> Log.d("FlutterBlueConnect", logMsg)
      else -> Log.v("FlutterBlueConnect", logMsg) // default verbose
    }
  }

  private val bondStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent?.action) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val bondState = intent.getIntExtra(
          BluetoothDevice.EXTRA_BOND_STATE,
          BluetoothDevice.BOND_NONE
        )

        val stateStr = when (bondState) {
          BluetoothDevice.BOND_BONDING -> "bonding"
          BluetoothDevice.BOND_BONDED -> "bonded"
          else -> "notBonded"
        }

        FlutterBlueDeviceManager.updateDevice(bondState = stateStr)

        device?.let {
          BluetoothEventEmitter.emit(
            "gap",
            "bondStateChanged",
            it.address,
            mapOf("bondState" to stateStr)
          )
        }
      }
    }
  }









  private fun getBtAddressViaReflection(adapter: BluetoothAdapter): String {
    return try {
      val m = adapter.javaClass.getDeclaredMethod("getAddress")
      m.isAccessible = true
      (m.invoke(adapter) as? String ?: "02:00:00:00:00:00").uppercase() // ensures non-null
    } catch (e: Exception) {
      android.util.Log.w("OOB", "Reflection getAddress() failed: ${e.message}")
      "00:00:00:00:00:00" // fallback string
    }
  }

  /**
   * Handles Flutter method calls for starting/stopping the Bluetooth scan.
   */
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {

      "startScan" -> {
        val scanRefreshTimeMs = call.argument<Int>("refreshTimeMs") ?: 500
        FlutterBlueGapManager.startScan(scanRefreshTimeMs)
      }

      "stopScan" -> {
        FlutterBlueGapManager.stopScan()
      }

      "connect" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val timeoutMillis = call.argument<Int>("timeout") ?: 10000
        FlutterBlueGapManager.connect(bluetoothAddress, timeoutMillis, result)
      }

      "disconnect" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        FlutterBlueGapManager.disconnect(bluetoothAddress)
      }

      "l2capChannelOpen" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val psm = call.argument<Int>("psm") ?: return result.error("INVALID_ARGUMENT", "Missing PSM parameter.", null)
        val secure = call.argument<Boolean>("secure") ?: false
        val timeoutMillis = call.argument<Int>("timeout") ?: 5000

        if (bluetoothAddress == null) {
          FlutterBlueLog.error("Cannot open L2CAP channel, reason: INVALID_ADDRESS")
          return
        }

        FlutterBlueL2capManager.openChannel(bluetoothAddress, psm, secure, timeoutMillis)
      }

      "l2capChannelClose" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        if (bluetoothAddress == null) {
          FlutterBlueLog.error("Missing bluetoothAddress parameter.")
          return
        }

        FlutterBlueL2capManager.closeChannel(bluetoothAddress, true)
      }

      "l2capSend" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val payload = call.argument<ByteArray>("data")

        if (bluetoothAddress == null || payload == null) {
          FlutterBlueLog.error("Missing bluetoothAddress or data")
          return
        }

        FlutterBlueL2capManager.sendData(bluetoothAddress, payload)
      }

      "printAllBluetoothMethods" -> {
//        try {
//          val sb = StringBuilder()
//
//          // ========== BluetoothAdapter Methods ==========
//          sb.append("\n========== BluetoothAdapter ALL Methods ==========\n")
//
//          bluetoothAdapter?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
//            sb.append("${method.name}(")
//            method.parameterTypes.forEachIndexed { index, param ->
//              sb.append(param.simpleName)
//              if (index < method.parameterTypes.size - 1) sb.append(", ")
//            }
//            sb.append("): ${method.returnType.simpleName}\n")
//          }
//
//          // ========== BluetoothDevice Methods ==========
//          sb.append("\n========== BluetoothDevice ALL Methods ==========\n")
//
//          val sampleDevice = bluetoothAdapter?.bondedDevices?.firstOrNull()
//            ?: bluetoothAdapter?.getRemoteDevice("00:00:00:00:00:00")
//
//          sampleDevice?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
//            sb.append("${method.name}(")
//            method.parameterTypes.forEachIndexed { index, param ->
//              sb.append(param.simpleName)
//              if (index < method.parameterTypes.size - 1) sb.append(", ")
//            }
//            sb.append("): ${method.returnType.simpleName}\n")
//          }
//
//          // ========== OOB-Related Methods Only ==========
//          sb.append("\n========== OOB-Related Methods ==========\n")
//          sb.append("--- BluetoothAdapter ---\n")
//
//          bluetoothAdapter?.javaClass?.methods?.filter {
//            it.name.contains("oob", ignoreCase = true) ||
//              it.name.contains("pairing", ignoreCase = true) ||
//              it.name.contains("bond", ignoreCase = true)
//          }?.forEach { method ->
//            sb.append("${method.name}(")
//            method.parameterTypes.forEachIndexed { index, param ->
//              sb.append(param.simpleName)
//              if (index < method.parameterTypes.size - 1) sb.append(", ")
//            }
//            sb.append("): ${method.returnType.simpleName}\n")
//          }
//
//          sb.append("\n--- BluetoothDevice ---\n")
//          sampleDevice?.javaClass?.methods?.filter {
//            it.name.contains("oob", ignoreCase = true) ||
//              it.name.contains("pairing", ignoreCase = true) ||
//              it.name.contains("bond", ignoreCase = true)
//          }?.forEach { method ->
//            sb.append("${method.name}(")
//            method.parameterTypes.forEachIndexed { index, param ->
//              sb.append(param.simpleName)
//              if (index < method.parameterTypes.size - 1) sb.append(", ")
//            }
//            sb.append("): ${method.returnType.simpleName}\n")
//          }
//
//          // ========== Check for OobData class ==========
//          sb.append("\n========== OobData Class Check ==========\n")
//          try {
//            val oobDataClass = Class.forName("android.bluetooth.OobData")
//            sb.append("✅ OobData class EXISTS\n")
//            sb.append("Constructors:\n")
//            oobDataClass.constructors.forEach { constructor ->
//              sb.append("  OobData(")
//              constructor.parameterTypes.forEachIndexed { index, param ->
//                sb.append(param.simpleName)
//                if (index < constructor.parameterTypes.size - 1) sb.append(", ")
//              }
//              sb.append(")\n")
//            }
//            sb.append("Methods:\n")
//            oobDataClass.methods.sortedBy { it.name }.forEach { method ->
//              sb.append("  ${method.name}(")
//              method.parameterTypes.forEachIndexed { index, param ->
//                sb.append(param.simpleName)
//                if (index < method.parameterTypes.size - 1) sb.append(", ")
//              }
//              sb.append("): ${method.returnType.simpleName}\n")
//            }
//          } catch (e: ClassNotFoundException) {
//            sb.append("❌ OobData class NOT FOUND\n")
//          }
//
//          // ========== Check Android Version Info ==========
//          sb.append("\n========== Device Info ==========\n")
//          sb.append("Android Version: ${Build.VERSION.RELEASE}\n")
//          sb.append("SDK Int: ${Build.VERSION.SDK_INT}\n")
//          sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
//
//          sb.append("\n==============================================\n")
//
//          val output = sb.toString()
//          logMessage("info", output)
//
//          result.success(mapOf(
//            "methods" to output
//          ))
//
//        } catch (e: Exception) {
//          logMessage("error", "Failed to print methods: ${e.message}")
//          result.error("PRINT_METHODS_ERROR", e.message, null)
//        }
      }

      "generateLocalLeScOobData" -> {
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

      /**
       * Handles method startPairing.
       */
      "startPairing" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//
//        if (bluetoothAddress == null) {
//          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
//          return
//        }
//
//        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
//        if (device == null) {
//          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
//          return
//        }
//
//        try {
//          val success = device.createBond()
//          if (success) {
//            result.success("Pairing initiated with $bluetoothAddress")
//          } else {
//            result.error("PAIRING_FAILED", "createBond() returned false", null)
//          }
//        } catch (e: Exception) {
//          result.error("PAIRING_ERROR", e.message, null)
//        }
      }

      /**
       * Handles method startPairingOob.
       * Initiates Out-of-Band pairing using provided OOB data.
       */
      "startPairingOob" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//        val oobData = call.argument<ByteArray>("oobData")
//
//        if (bluetoothAddress == null) {
//          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
//          return
//        }
//
//        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
//        if (device == null) {
//          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
//          return
//        }
//
//        try {
//          // Check Android version for OOB support
//          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
//
//            // For Android 10 (Q) and above - OOB pairing with data
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && oobData != null) {
//              logMessage("info", "Starting OOB pairing with data for $bluetoothAddress")
//
//              // Parse OOB data - typically contains confirmation value and randomizer
//              // Format depends on your OOB exchange mechanism
//              if (oobData.size >= 32) {
//                val confirmationValue = oobData.copyOfRange(0, 16)
//                val randomizer = oobData.copyOfRange(16, 32)
//
//                // Use reflection to access setOobData method
//                val method = device.javaClass.getMethod(
//                  "setOobData",
//                  ByteArray::class.java,
//                  ByteArray::class.java
//                )
//                method.invoke(device, confirmationValue, randomizer)
//
//                logMessage("info", "OOB data set successfully")
//              } else {
//                result.error("INVALID_OOB_DATA", "OOB data must be at least 32 bytes", null)
//                return
//              }
//            }
//
//            // Initiate the bonding process
//            val success = device.createBond()
//
//            if (success) {
//              logMessage("info", "OOB pairing initiated with $bluetoothAddress")
//              result.success("OOB pairing initiated with $bluetoothAddress")
//            } else {
//              logMessage("error", "createBond() returned false for $bluetoothAddress")
//              result.error("PAIRING_FAILED", "createBond() returned false", null)
//            }
//
//          } else {
//            result.error(
//              "UNSUPPORTED_VERSION",
//              "OOB pairing requires Android 4.4 (KitKat) or higher",
//              null
//            )
//          }
//
//        } catch (e: NoSuchMethodException) {
//          logMessage("error", "OOB method not available: ${e.message}")
//          result.error("METHOD_NOT_AVAILABLE", "OOB pairing method not available on this device", null)
//        } catch (e: Exception) {
//          logMessage("error", "OOB pairing error: ${e.message}")
//          result.error("PAIRING_ERROR", e.message, null)
//        }
      }

      "startEncryptConnection" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//
//        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
//        if (device == null) {
//          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
//          return
//        }
//
//        try {
//          // Reflection để gọi hidden API
//          val method = device.javaClass.getDeclaredMethod("startEncryptConnection")
//          method.isAccessible = true
//          val success = method.invoke(device) as Boolean
//
//          if (success) {
//            result.success("Encrypt connection started for $bluetoothAddress")
//          } else {
//            result.error("ENCRYPT_FAILED", "startEncryptConnection() returned false", null)
//          }
//
//        } catch (e: Exception) {
//          result.error("ENCRYPT_FAILED", e.message, null)
//        }
      }


      /**
       * Handles remove bond.
       */
      "deleteBond" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//
//        if (bluetoothAddress == null) {
//          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
//          return
//        }
//
//        try {
//          val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
//
//          val method = device?.javaClass?.getMethod("removeBond")
//          val success = method?.invoke(device) as Boolean
//
//          if (success) {
//            Log.i("FlutterBlueConnect", "removeBond($bluetoothAddress): success")
//            result.success(true)
//          } else {
//            Log.w("FlutterBlueConnect", "removeBond($bluetoothAddress): failed")
//            result.success(false)
//          }
//        } catch (e: Exception) {
//          Log.e("FlutterBlueConnect", "Error removing bond for $bluetoothAddress", e)
//          result.error("REMOVE_BOND_ERROR", e.message, null)
//        }
      }

      "startChannelSounding" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//        if (bluetoothAddress == null) {
//          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
//          return
//        }
//
//        if (channelSoundingManager == null) {
//          result.error("MANAGER_NOT_INITIALIZED", "ChannelSoundingManager not initialized", null)
//          return
//        }
//
//        try {
//          channelSoundingManager?.startChannelSounding(bluetoothAddress)
//
//          result.success("Channel sounding started for $bluetoothAddress")
//        } catch (e: Exception) {
//          result.error("START_FAILED", e.message, null)
//        }
      }

      "stopChannelSounding" -> {
//        val bluetoothAddress = call.argument<String>("bluetoothAddress")
//        if (bluetoothAddress == null) {
//          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
//          return
//        }
//
//        if (channelSoundingManager == null) {
//          result.error("MANAGER_NOT_INITIALIZED", "ChannelSoundingManager not initialized", null)
//          return
//        }
//
//        try {
//          channelSoundingManager?.stopChannelSounding(bluetoothAddress)
//          result.success("Channel sounding stopped for $bluetoothAddress")
//        } catch (e: Exception) {
//          result.error("STOP_FAILED", e.message, null)
//        }
      }

      /**
       * Handles undefined method.
       */
      else -> result.notImplemented()
    }
  }

  /**
   * Sets up the plugin when attached to the Flutter engine.
   * Initializes MethodChannel for commands and EventChannel for real-time scan updates.
   */
  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {

    methodChannel = MethodChannel(binding.binaryMessenger, "flutter_blue_connect")
    methodChannel.setMethodCallHandler(this)

    appContext = binding.applicationContext

    bluetoothManager = binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager?.adapter

    bluetoothEventChannel = EventChannel(binding.binaryMessenger, "channel_bluetooth_events")
    bluetoothEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        bluetoothEventSink = events
        BluetoothEventEmitter.eventSink = events
      }

      override fun onCancel(arguments: Any?) {
        bluetoothEventSink = null
        BluetoothEventEmitter.eventSink = null
      }
    })

    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    appContext.registerReceiver(bondStateReceiver, filter)

    // Start the encryption checker automatically
    FlutterBlueGapManager.onAttachedToEngine(binding)
    FlutterBlueL2capManager.onAttachedToEngine(binding)
    BluetoothEncryptionMonitor.start()

    channelSoundingManager = ChannelSoundingManager(appContext)
  }

  /**
   * Cleans up when the plugin is detached from the Flutter engine.
   * Removes references to channels and event sinks.
   */
  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)

    bluetoothEventSink = null

    FlutterBlueGapManager.onDetachedFromEngine()
    FlutterBlueL2capManager.onDetachedFromEngine()


    // Stop checking when plugin is detached
    BluetoothEncryptionMonitor.stop()

    try {
      appContext.unregisterReceiver(bondStateReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w("FlutterBlueConnect", "Receiver already unregistered: ${e.message}")
    }
  }
}
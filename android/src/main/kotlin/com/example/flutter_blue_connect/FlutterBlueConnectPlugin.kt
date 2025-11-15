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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Build
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

import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import javax.crypto.spec.IvParameterSpec
import kotlin.experimental.xor

import android.os.IBinder
import android.annotation.SuppressLint

import java.util.concurrent.Executor
import java.lang.reflect.Proxy
import java.util.concurrent.Executors



import com.example.flutter_blue_connect.FlutterBlueConnectPlugin

/**
 * Flutter plugin to manage Bluetooth Low Energy (BLE) scanning in Android.
 * Communicates with Flutter using MethodChannel for commands and EventChannel for real-time updates.
 */
class FlutterBlueConnectPlugin: FlutterPlugin, MethodChannel.MethodCallHandler {

  data class ScannedDevice(
    val device: BluetoothDevice,
    val advData: List<Int>,
    val rssi: Int,
    val timestamp: Long
  )


  // Channels for communication with Flutter
  private lateinit var methodChannel: MethodChannel
  private lateinit var scanChannel: EventChannel
  private lateinit var bluetoothEventChannel: EventChannel
  private lateinit var appContext: Context

  // Bluetooth adapter reference
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothManager: BluetoothManager? = null
//  private var activeGattConnections = ConcurrentHashMap<String, BluetoothGatt>()

  companion object {
    // 🔧 make it accessible from anywhere
    val activeGattConnections = ConcurrentHashMap<String, BluetoothGatt>()
  }

  // Sink for sending scan results to Flutter
  private var scanResultSink: EventChannel.EventSink? = null
  private var bluetoothEventSink: EventChannel.EventSink? = null

  // Maps to store scanned Bluetooth devices with their timestamps
  private val mapScannedBluetoothDevice = ConcurrentHashMap<String, ScannedDevice>()
  private var mapPreviousScannedBluetoothDevice = emptyMap<String, ScannedDevice>()

  private val pendingConnectionResults = mutableMapOf<String, MethodChannel.Result>()
  private val linkLayerConnectionTimeouts = mutableMapOf<String, Pair<Handler, Runnable>>()
  private val l2capConnectionTimeouts = mutableMapOf<String, Pair<Handler, Runnable>>()

  private var scanRefreshTimeMs: Int = 500

  private var generatedOobData: Map<String, Any>? = null

  private val activeL2capSockets = ConcurrentHashMap<String, BluetoothSocket>()

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

  /**
   * Handler and Runnable to periodically clean up stale Bluetooth devices.
   *
   * The cleanup process runs every second (1000ms) and removes devices
   * that have not been seen for more than 2 seconds (2000ms).
   * This ensures the scanned device list stays up-to-date and doesn't
   * include outdated devices.
   */
  private val handlerTimerCleanup = Handler(Looper.getMainLooper())
  private val runnableTimerCleanup = object : Runnable {
    override fun run() {
      val currentTime = System.currentTimeMillis()
      mapScannedBluetoothDevice.entries.removeIf { (_, v) ->
        (currentTime - v.timestamp) > 2000
      }
      handlerTimerCleanup.postDelayed(this, 1000)
    }
  }

  val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      val address = gatt.device.address
      val pendingResult = pendingConnectionResults.remove(address)

      if (newState == BluetoothProfile.STATE_CONNECTED) {
        linkLayerConnectionTimeouts[address]?.first?.removeCallbacks(linkLayerConnectionTimeouts[address]?.second!!)
        linkLayerConnectionTimeouts.remove(address)

        logMessage("info", "Connected to GATT server")
        gatt.discoverServices()

        val bondState = when (gatt.device.bondState) {
          BluetoothDevice.BOND_BONDING -> "bonding"
          BluetoothDevice.BOND_BONDED -> "bonded"
          else -> "notBonded"
        }

        val encryptState = try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val method = BluetoothGatt::class.java.getMethod("isEncrypted")
            val result = method.invoke(gatt) as? Boolean ?: false
            if (result) "encrypted" else "notEncrypted"
          } else {
            if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) "encrypted" else "notEncrypted"
          }
        } catch (e: Exception) {
          "notEncrypted"
        }

        pendingResult?.success("Connected to $address")

        FlutterBlueDeviceManager.updateDevice(
          name = gatt.device.name,
          address = address,
          linkLayerState = "connected",
          l2capState = "disconnected",
          bondState = bondState,
          encryptionState = encryptState
        )

        BluetoothEventEmitter.emit(
          "gap",
          "connected",
          address,
        )

      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        BluetoothEncryptionMonitor.resetState()

        linkLayerConnectionTimeouts.remove(address)

        logMessage("info", "Disconnected from GATT server")

        // If connection failed before success
        pendingResult?.error("CONNECTION_FAILED", "Failed to connect to $address", null)

        BluetoothEventEmitter.emit("gap", "disconnected", address)

        // Only clear device after event disconnected was emitted
        FlutterBlueDeviceManager.clear()
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        logMessage("info", "Services discovered: ${gatt.services}")

        BluetoothEventEmitter.emit("gatt", "servicesDiscovered", gatt.device.address, mapOf(
          "services" to gatt.services.map { it.uuid.toString() }
        ))
      }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        logMessage("info", "Read characteristic: ${characteristic.uuid}, value: ${characteristic.value}")

        BluetoothEventEmitter.emit("gatt", "read", gatt.device.address, mapOf(
          "uuid" to characteristic.uuid.toString(),
          "value" to characteristic.value.toList()
        ))
      }
    }
  }

  /**
   * Handler and Runnable to check for Bluetooth scan result changes every 500ms.
   *
   * This mechanism ensures that only **updated** device lists are sent to Flutter.
   * The runnable compares the current scanned device list (`mapScannedBluetoothDevice`)
   * with the previously stored snapshot (`mapPreviousScannedBluetoothDevice`).
   * If the device list has changed, it triggers an event (`scanResultSink.success()`)
   * to send new data to the Flutter side.
   *
   * Execution cycle:
   * - Runs every 'scanRefreshTimeMs' to detect updates.
   * - If changes are found, sends new scan results.
   * - Otherwise, waits for the next scheduled check.
   */

  private val handlerScanResultChangedCheck = Handler(Looper.getMainLooper())
  private val runnableScanResultChangedCheck = object : Runnable {
    override fun run() {
      // Check if the scanned device list has changed
      val hasChanged = mapScannedBluetoothDevice != mapPreviousScannedBluetoothDevice
      mapPreviousScannedBluetoothDevice = mapScannedBluetoothDevice.toMap()

      // If there's a change, send updated device list to Flutter
      if (hasChanged) {
        val devicesList = mapScannedBluetoothDevice.values.map { scanned ->
          mapOf(
            "name" to (scanned.device.name ?: "Unnamed"),
            "bluetoothAddress" to scanned.device.address,
            "advData" to scanned.advData,
            "rssi" to scanned.rssi
          )
        }
        scanResultSink?.success(devicesList)
      }
      // Schedule next check
      handlerScanResultChangedCheck.postDelayed(this, scanRefreshTimeMs.toLong()) // Schedule next execution
    }
  }

  /**
   * Callback function that is triggered when a Bluetooth scan result is found.
   * Updates the device list with the latest timestamp.
   */
  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val device = result.device
      val advBytes = result.scanRecord?.bytes
      val advData = advBytes?.map { it.toInt() and 0xFF } ?: emptyList()
      val rssi = result.rssi

      mapScannedBluetoothDevice[device.address] = ScannedDevice(
        device,
        advData,
        rssi,
        System.currentTimeMillis())
    }
  }

  fun listenL2capEvent(socket: BluetoothSocket, address: String) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val input = socket.inputStream
        val buffer = ByteArray(65536)

        while (true) {
          val bytesRead = input.read(buffer)
          if (bytesRead > 0) {
            val data = buffer.copyOf(bytesRead)

            val hexString = data.joinToString(" ") { "%02X".format(it) }
            logMessage("info", "L2CAP RX | btaddr=$address, len=${data.size}, payload=$hexString")

            BluetoothEventEmitter.emit("l2cap", "dataReceived", address,
              mapOf(
                "data" to data,
                "hex" to data.joinToString(" ") { "%02X".format(it) },
                "length" to data.size
              )
            )
          }
        }
      } catch (e: IOException) {
        Log.e("FlutterBlueConnect", "Disconnected from $address: ${e.message}")
        activeL2capSockets.remove(address)

        FlutterBlueDeviceManager.updateDevice(l2capState = "disconnected")
        BluetoothEventEmitter.emit("l2cap", "disconnected", address)
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun l2capChannelOpen(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val psm = call.argument<Int>("psm") ?: return result.error("INVALID_ARGUMENT", "Missing PSM parameter.", null)
    val secure = call.argument<Boolean>("secure") ?: false
    val timeoutMillis = call.argument<Int>("timeout") ?: 5000

    if (bluetoothAdapter?.isEnabled != true) {
      result.error("BLUETOOTH_DISABLED", "Bluetooth is not enabled.", null)
      return
    }

    if (bluetoothAddress == null) {
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      result.error("DEVICE_NOT_FOUND", "Open l2cap failed, could not find device with address $bluetoothAddress", null)
      return
    }

    val manager = bluetoothManager ?: run {
      result.error("NO_BLUETOOTH_MANAGER", "BluetoothManager is not initialized.", null)
      return
    }
    val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
    val isConnected = connectedDevices?.any { it.address == bluetoothAddress } == true

    if (!isConnected) {
      result.error("NO_LINK_LAYER", "Device is not connected at GAP level", null)
      logMessage("info", "Device $bluetoothAddress is not connected at GAP level")
      return
    }

    val handler = Handler(Looper.getMainLooper())
    val timeoutRunnable = Runnable {
      activeL2capSockets[bluetoothAddress]?.close()
      activeL2capSockets.remove(bluetoothAddress)
      Log.w("L2CAPTimeout", "L2CAP connection to $bluetoothAddress timed out.")
      result.error("L2CAP_TIMEOUT", "L2CAP connection to $bluetoothAddress timed out after ${timeoutMillis}ms", null)
    }

    // Start timeout countdown
    handler.postDelayed(timeoutRunnable, timeoutMillis.toLong())
    l2capConnectionTimeouts[bluetoothAddress] = Pair(handler, timeoutRunnable)

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val socket = if (secure) {
          device.createL2capChannel(psm)
        } else {
          device.createInsecureL2capChannel(psm)
        }

        socket.connect()

        // Cancel timeout if successful
        withContext(Dispatchers.Main) {
          handler.removeCallbacks(timeoutRunnable)
          l2capConnectionTimeouts.remove(bluetoothAddress)

          activeL2capSockets[bluetoothAddress] = socket

          FlutterBlueDeviceManager.updateDevice(l2capState = "connected")
          BluetoothEventEmitter.emit("l2cap", "connected", bluetoothAddress)

          result.success("L2CAP channel to $bluetoothAddress opened.")
        }

        listenL2capEvent(socket, bluetoothAddress)
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          handler.removeCallbacks(timeoutRunnable)
          l2capConnectionTimeouts.remove(bluetoothAddress)
          result.error("L2CAP_ERROR", e.message, null)
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun l2capChannelClose(bluetoothAddress: String, result: MethodChannel.Result) {
    val socket = activeL2capSockets[bluetoothAddress]

    if (socket == null) {
      result.error("L2CAP_NOT_FOUND", "No active L2CAP channel for $bluetoothAddress", null)
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        logMessage("info", "Closing L2CAP channel for $bluetoothAddress")

        val hadListener = activeL2capSockets.containsKey(bluetoothAddress)
        socket.close()
        activeL2capSockets.remove(bluetoothAddress)

        withContext(Dispatchers.Main) {
//          FlutterBlueDeviceManager.updateDevice(l2capState = "disconnected")
//          BluetoothEventEmitter.emit("l2cap", "disconnected", bluetoothAddress)
          if (!hadListener) {
            // No listener, emit manually
            BluetoothEventEmitter.emit("l2cap", "disconnected", bluetoothAddress)
          }

          result.success("L2CAP channel for $bluetoothAddress closed successfully.")
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          result.error("L2CAP_CLOSE_ERROR", "Failed to close L2CAP: ${e.message}", null)
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun l2capSend(
    bluetoothAddress: String,
    data: ByteArray,
    result: MethodChannel.Result
  ) {
    val socket = activeL2capSockets[bluetoothAddress]
    if (socket == null) {
      result.error("L2CAP_NOT_CONNECTED", "No active L2CAP channel for $bluetoothAddress", null)
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val hexString = data.joinToString(" ") { "%02X".format(it) }

        logMessage("info", "L2CAP TX | btaddr=$bluetoothAddress, len=${data.size}, payload=$hexString")

        socket.outputStream.write(data)
        socket.outputStream.flush()

        withContext(Dispatchers.Main) {
          result.success("Sent ${data.size} bytes to $bluetoothAddress")
        }
      } catch (e: IOException) {
        withContext(Dispatchers.Main) {
          result.error("L2CAP_SEND_ERROR", "Failed to send data: ${e.message}", null)
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
      /**
       * Handles method startscan.
       */
      "startScan" -> {
        scanRefreshTimeMs = call.argument<Int>("refreshTimeMs") ?: 500
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val scanFilters = listOf<ScanFilter>()  // Apply filters if needed
        val settings = ScanSettings.Builder()
          .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
          .build()
        scanner?.startScan(scanFilters, settings, scanCallback)

        handlerScanResultChangedCheck.post(runnableScanResultChangedCheck)
        handlerTimerCleanup?.postDelayed(runnableTimerCleanup!!, 1000)
        result.success("Bluetooth scanning started.")
      }

      /**
       * Handles method stopScan.
       */
      "stopScan" -> {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        handlerScanResultChangedCheck.removeCallbacks(runnableScanResultChangedCheck)
        handlerTimerCleanup?.removeCallbacks(runnableTimerCleanup!!) // Stop the cleanup loop
        result.success("Bluetooth scanning stopped.")
      }

      /**
       * Handles method connect.
       */
      "connect" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val timeoutMillis = call.argument<Int>("timeout") ?: 10000

        if (bluetoothAdapter?.isEnabled != true) {
          result.error("BLUETOOTH_DISABLED", "Bluetooth is not enabled.", null)
          return
        }

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
        val gatt = device?.connectGatt(appContext, false, gattCallback)
        if (gatt != null) {
          activeGattConnections[device.address] = gatt
          pendingConnectionResults[device.address] = result

          val handler = Handler(Looper.getMainLooper())
          val timeoutRunnable = Runnable {
            pendingConnectionResults.remove(device.address)?.error(
              "CONNECTION_TIMEOUT",
              "Connection to ${device.address} timed out after ${timeoutMillis}ms",
              null
            )
            gatt.disconnect()
            gatt.close()
            activeGattConnections.remove(device.address)
          }

          handler.postDelayed(timeoutRunnable, timeoutMillis.toLong())
          linkLayerConnectionTimeouts[device.address] = Pair(handler, timeoutRunnable)
        } else {
          result.error("CONNECTION_FAILED", "Failed to initiate connection.", null)
        }
      }

      /**
       * Handles method disconnect.
       */
      "disconnect" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        val gatt = activeGattConnections[bluetoothAddress]
        if (gatt == null) {
          result.error("NOT_CONNECTED", "No active connection for $bluetoothAddress", null)
          return
        }

        // Close L2CAP if open
        activeL2capSockets[bluetoothAddress]?.let {
          try {
            it.close()
          } catch (e: Exception) {
            Log.w("L2CAPClose", "Failed to close L2CAP: ${e.message}")
          }
          activeL2capSockets.remove(bluetoothAddress)
        }

        // ✅ Just disconnect, don't close yet — let callback handle it
        gatt.disconnect()

        // Optional: close later after delay
        Handler(Looper.getMainLooper()).postDelayed({
          gatt.close()
          activeGattConnections.remove(bluetoothAddress)
        }, 500)

        result.success("Disconnecting from $bluetoothAddress ...")
      }

      /**
       * Handles method l2capChannelOpen.
       */
      "l2capChannelOpen" -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          l2capChannelOpen(call, result)
        } else {
          result.error("UNSUPPORTED_VERSION", "L2CAP requires Android 10 or higher.", null)
        }
      }

      /**
       * Handles method l2capChannelClose.
       */
      "l2capChannelClose" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }
        l2capChannelClose(bluetoothAddress, result)
      }

      /**
       * Handles method l2capSend.
       */
      "l2capSend" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val payload = call.argument<ByteArray>("data")

        if (bluetoothAddress == null || payload == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress or data", null)
          return
        }

        l2capSend(bluetoothAddress, payload, result)
      }

      /**
       * Handles method printAllBluetoothMethods.
       * Prints ALL available Bluetooth methods including inherited ones.
       */
      "printAllBluetoothMethods" -> {
        try {
          val sb = StringBuilder()

          // ========== BluetoothAdapter Methods ==========
          sb.append("\n========== BluetoothAdapter ALL Methods ==========\n")

          bluetoothAdapter?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
            sb.append("${method.name}(")
            method.parameterTypes.forEachIndexed { index, param ->
              sb.append(param.simpleName)
              if (index < method.parameterTypes.size - 1) sb.append(", ")
            }
            sb.append("): ${method.returnType.simpleName}\n")
          }

          // ========== BluetoothDevice Methods ==========
          sb.append("\n========== BluetoothDevice ALL Methods ==========\n")

          val sampleDevice = bluetoothAdapter?.bondedDevices?.firstOrNull()
            ?: bluetoothAdapter?.getRemoteDevice("00:00:00:00:00:00")

          sampleDevice?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
            sb.append("${method.name}(")
            method.parameterTypes.forEachIndexed { index, param ->
              sb.append(param.simpleName)
              if (index < method.parameterTypes.size - 1) sb.append(", ")
            }
            sb.append("): ${method.returnType.simpleName}\n")
          }

          // ========== OOB-Related Methods Only ==========
          sb.append("\n========== OOB-Related Methods ==========\n")
          sb.append("--- BluetoothAdapter ---\n")

          bluetoothAdapter?.javaClass?.methods?.filter {
            it.name.contains("oob", ignoreCase = true) ||
              it.name.contains("pairing", ignoreCase = true) ||
              it.name.contains("bond", ignoreCase = true)
          }?.forEach { method ->
            sb.append("${method.name}(")
            method.parameterTypes.forEachIndexed { index, param ->
              sb.append(param.simpleName)
              if (index < method.parameterTypes.size - 1) sb.append(", ")
            }
            sb.append("): ${method.returnType.simpleName}\n")
          }

          sb.append("\n--- BluetoothDevice ---\n")
          sampleDevice?.javaClass?.methods?.filter {
            it.name.contains("oob", ignoreCase = true) ||
              it.name.contains("pairing", ignoreCase = true) ||
              it.name.contains("bond", ignoreCase = true)
          }?.forEach { method ->
            sb.append("${method.name}(")
            method.parameterTypes.forEachIndexed { index, param ->
              sb.append(param.simpleName)
              if (index < method.parameterTypes.size - 1) sb.append(", ")
            }
            sb.append("): ${method.returnType.simpleName}\n")
          }

          // ========== Check for OobData class ==========
          sb.append("\n========== OobData Class Check ==========\n")
          try {
            val oobDataClass = Class.forName("android.bluetooth.OobData")
            sb.append("✅ OobData class EXISTS\n")
            sb.append("Constructors:\n")
            oobDataClass.constructors.forEach { constructor ->
              sb.append("  OobData(")
              constructor.parameterTypes.forEachIndexed { index, param ->
                sb.append(param.simpleName)
                if (index < constructor.parameterTypes.size - 1) sb.append(", ")
              }
              sb.append(")\n")
            }
            sb.append("Methods:\n")
            oobDataClass.methods.sortedBy { it.name }.forEach { method ->
              sb.append("  ${method.name}(")
              method.parameterTypes.forEachIndexed { index, param ->
                sb.append(param.simpleName)
                if (index < method.parameterTypes.size - 1) sb.append(", ")
              }
              sb.append("): ${method.returnType.simpleName}\n")
            }
          } catch (e: ClassNotFoundException) {
            sb.append("❌ OobData class NOT FOUND\n")
          }

          // ========== Check Android Version Info ==========
          sb.append("\n========== Device Info ==========\n")
          sb.append("Android Version: ${Build.VERSION.RELEASE}\n")
          sb.append("SDK Int: ${Build.VERSION.SDK_INT}\n")
          sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")

          sb.append("\n==============================================\n")

          val output = sb.toString()
          logMessage("info", output)

          result.success(mapOf(
            "methods" to output
          ))

        } catch (e: Exception) {
          logMessage("error", "Failed to print methods: ${e.message}")
          result.error("PRINT_METHODS_ERROR", e.message, null)
        }
      }

      "generateLocalLeScOobData" -> {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
          result.error("NO_ADAPTER", "BluetoothAdapter not available", null)
          return
        }

        try {
          // Find the hidden method via reflection
          val method = bluetoothAdapter.javaClass.declaredMethods.firstOrNull {
            it.name == "generateLocalOobData"
          }

          if (method == null) {
            result.error("METHOD_NOT_FOUND", "generateLocalOobData not available", null)
            return
          }

          // Load hidden classes reflectively
          val callbackClass = Class.forName("android.bluetooth.BluetoothAdapter\$OobDataCallback")
          val oobDataClass = Class.forName("android.bluetooth.OobData")

          // Build dynamic proxy for the callback
          val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
          ) { _, method, args ->
            if (method.name == "onOobData") {
              val arg = args?.getOrNull(0)
              Log.d("FlutterBlueConnect", "OOB callback arg type: ${arg?.javaClass?.name}")

              if (oobDataClass.isInstance(arg)) {
                val oobData = arg

                fun getBytes(fn: String): ByteArray? = try {
                  oobDataClass.getMethod(fn).invoke(oobData) as? ByteArray
                } catch (e: Exception) {
                  Log.e("FlutterBlueConnect", "Error calling $fn: ${e.message}")
                  null
                }

                val map = mapOf(
                  "confirmationHash" to (getBytes("getConfirmationHash")?.joinToString(",") ?: ""),
                  "randomizerHash" to (getBytes("getRandomizerHash")?.joinToString(",") ?: ""),
                  "deviceAddressWithType" to (getBytes("getDeviceAddressWithType")?.joinToString(",") ?: ""),
                  "leTemporaryKey" to (getBytes("getLeTemporaryKey")?.joinToString(",") ?: "")
                )

                Handler(Looper.getMainLooper()).post {
                  Log.d("FlutterBlueConnect", "✅ OOB DATA GENERATED: $map")
                  result.success(map)
                }
              } else {
                Log.w("FlutterBlueConnect", "OOB callback returned unexpected type: ${arg?.javaClass}")
                Handler(Looper.getMainLooper()).post {
                  result.error("NULL_OOB", "OOB callback returned invalid type", null)
                }
              }
            }
            null
          }

          // Inline Executor proxy
          val executorInterface = Class.forName("java.util.concurrent.Executor")
          val executorProxy = java.lang.reflect.Proxy.newProxyInstance(
            executorInterface.classLoader,
            arrayOf(executorInterface)
          ) { _, m, args ->
            if (m.name == "execute") {
              val runnable = args?.getOrNull(0) as? Runnable
              runnable?.run()
            }
            null
          }

          // TRANSPORT_LE = 2
          method.invoke(bluetoothAdapter, 2, executorProxy, callbackProxy)

        } catch (e: Exception) {
          result.error("OOB_ERROR", e.toString(), null)
        }
      }

      /**
       * Handles method startPairing.
       */
      "startPairing" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
        if (device == null) {
          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
          return
        }

        try {
          val success = device.createBond()
          if (success) {
            result.success("Pairing initiated with $bluetoothAddress")
          } else {
            result.error("PAIRING_FAILED", "createBond() returned false", null)
          }
        } catch (e: Exception) {
          result.error("PAIRING_ERROR", e.message, null)
        }
      }

      /**
       * Handles method startPairingOob.
       * Initiates Out-of-Band pairing using provided OOB data.
       */
      "startPairingOob" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val oobData = call.argument<ByteArray>("oobData")

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
        if (device == null) {
          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
          return
        }

        try {
          // Check Android version for OOB support
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {

            // For Android 10 (Q) and above - OOB pairing with data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && oobData != null) {
              logMessage("info", "Starting OOB pairing with data for $bluetoothAddress")

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

                logMessage("info", "OOB data set successfully")
              } else {
                result.error("INVALID_OOB_DATA", "OOB data must be at least 32 bytes", null)
                return
              }
            }

            // Initiate the bonding process
            val success = device.createBond()

            if (success) {
              logMessage("info", "OOB pairing initiated with $bluetoothAddress")
              result.success("OOB pairing initiated with $bluetoothAddress")
            } else {
              logMessage("error", "createBond() returned false for $bluetoothAddress")
              result.error("PAIRING_FAILED", "createBond() returned false", null)
            }

          } else {
            result.error(
              "UNSUPPORTED_VERSION",
              "OOB pairing requires Android 4.4 (KitKat) or higher",
              null
            )
          }

        } catch (e: NoSuchMethodException) {
          logMessage("error", "OOB method not available: ${e.message}")
          result.error("METHOD_NOT_AVAILABLE", "OOB pairing method not available on this device", null)
        } catch (e: Exception) {
          logMessage("error", "OOB pairing error: ${e.message}")
          result.error("PAIRING_ERROR", e.message, null)
        }
      }

      "startEncryptConnection" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")

        val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
        if (device == null) {
          result.error("DEVICE_NOT_FOUND", "Device not found for address $bluetoothAddress", null)
          return
        }

        try {
          // Reflection để gọi hidden API
          val method = device.javaClass.getDeclaredMethod("startEncryptConnection")
          method.isAccessible = true
          val success = method.invoke(device) as Boolean

          if (success) {
            result.success("Encrypt connection started for $bluetoothAddress")
          } else {
            result.error("ENCRYPT_FAILED", "startEncryptConnection() returned false", null)
          }

        } catch (e: Exception) {
          result.error("ENCRYPT_FAILED", e.message, null)
        }
      }


      /**
       * Handles remove bond.
       */
      "deleteBond" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        try {
          val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)

          val method = device?.javaClass?.getMethod("removeBond")
          val success = method?.invoke(device) as Boolean

          if (success) {
            Log.i("FlutterBlueConnect", "removeBond($bluetoothAddress): success")
            result.success(true)
          } else {
            Log.w("FlutterBlueConnect", "removeBond($bluetoothAddress): failed")
            result.success(false)
          }
        } catch (e: Exception) {
          Log.e("FlutterBlueConnect", "Error removing bond for $bluetoothAddress", e)
          result.error("REMOVE_BOND_ERROR", e.message, null)
        }
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

    scanChannel = EventChannel(binding.binaryMessenger, "flutter_blue_connect_scan")
    scanChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        scanResultSink = events
      }
      override fun onCancel(arguments: Any?) {
        scanResultSink = null
      }
    })

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
    BluetoothEncryptionMonitor.start()
  }

  /**
   * Cleans up when the plugin is detached from the Flutter engine.
   * Removes references to channels and event sinks.
   */
  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
    scanResultSink = null
    bluetoothEventSink = null

    handlerScanResultChangedCheck.removeCallbacks(runnableScanResultChangedCheck)
    handlerTimerCleanup.removeCallbacks(runnableTimerCleanup)

    // Stop checking when plugin is detached
    BluetoothEncryptionMonitor.stop()

    try {
      appContext.unregisterReceiver(bondStateReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w("FlutterBlueConnect", "Receiver already unregistered: ${e.message}")
    }
  }
}
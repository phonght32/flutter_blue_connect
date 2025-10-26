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

/**
 * Flutter plugin to manage Bluetooth Low Energy (BLE) scanning in Android.
 * Communicates with Flutter using MethodChannel for commands and EventChannel for real-time updates.
 */
class FlutterBlueConnectPlugin: FlutterPlugin, MethodChannel.MethodCallHandler {

  data class ScannedDevice(
    val device: BluetoothDevice,
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
  private var activeGattConnections = ConcurrentHashMap<String, BluetoothGatt>()

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

  private fun emitBluetoothEvent(
    layer: String,
    event: String,
    bluetoothAddress: String,
    data: Map<String, Any?> = emptyMap()
  ) {
    val payload = mapOf(
      "layer" to layer,
      "event" to event,
      "bluetoothAddress" to bluetoothAddress
    ) + data

    logMessage("info", "emit event {bluetoothAddress: $bluetoothAddress, layer: $layer, event $event}")

    Handler(Looper.getMainLooper()).post {
      bluetoothEventSink?.success(payload)
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

        device?.let {
          emitBluetoothEvent(
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
        emitBluetoothEvent(
          "gap",
          "connected",
          address,
          mapOf(
            "bondState" to bondState,
            "encryptState" to encryptState
          )
        )

      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        linkLayerConnectionTimeouts.remove(address)

        logMessage("info", "Disconnected from GATT server")

        // If connection failed before success
        pendingResult?.error("CONNECTION_FAILED", "Failed to connect to $address", null)

        emitBluetoothEvent("gap", "disconnected", address)
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        logMessage("info", "Services discovered: ${gatt.services}")

        emitBluetoothEvent("gatt", "servicesDiscovered", gatt.device.address, mapOf(
          "services" to gatt.services.map { it.uuid.toString() }
        ))
      }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        logMessage("info", "Read characteristic: ${characteristic.uuid}, value: ${characteristic.value}")

        emitBluetoothEvent("gatt", "read", gatt.device.address, mapOf(
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
            "name" to (scanned.device.name ?: "Unknown"),
            "bluetoothAddress" to scanned.device.address,
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
      val rssi = result.rssi
      mapScannedBluetoothDevice[device.address] = ScannedDevice(device, rssi, System.currentTimeMillis())
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

            emitBluetoothEvent("l2cap", "dataReceived", address,
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
        emitBluetoothEvent("l2cap", "disconnected", address)
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
          result.success("L2CAP channel to $bluetoothAddress opened.")
          emitBluetoothEvent("l2cap", "connected", bluetoothAddress)
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

        socket.close()
        activeL2capSockets.remove(bluetoothAddress)

        withContext(Dispatchers.Main) {
          emitBluetoothEvent("l2cap", "disconnected", bluetoothAddress)
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

        // 🔒 Check and close L2CAP channel
        val l2capChannel = activeL2capSockets[bluetoothAddress]
        if (l2capChannel != null) {
          try {
            l2capChannel.close() // or your library-specific close method
          } catch (e: Exception) {
            Log.w("L2CAPClose", "Failed to close L2CAP: ${e.message}")
          }
          activeL2capSockets.remove(bluetoothAddress)
        }

        gatt.disconnect()
        gatt.close() // Free system resources
        activeGattConnections.remove(bluetoothAddress)

        result.success("Disconnected from $bluetoothAddress")
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
      }

      override fun onCancel(arguments: Any?) {
        bluetoothEventSink = null
      }
    })

    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    appContext.registerReceiver(bondStateReceiver, filter)
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

    try {
      appContext.unregisterReceiver(bondStateReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w("FlutterBlueConnect", "Receiver already unregistered: ${e.message}")
    }
  }
}
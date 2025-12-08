package com.example.flutter_blue_connect

import androidx.annotation.RequiresApi

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothSocket

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import io.flutter.embedding.engine.plugins.FlutterPlugin

import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

object FlutterBlueL2capManager {
  // Bluetooth adapter reference
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothManager: BluetoothManager? = null

  private val l2capConnectionTimeouts = mutableMapOf<String, Pair<Handler, Runnable>>()
  private val activeL2capSockets = ConcurrentHashMap<String, BluetoothSocket>()

  fun getSocket(address: String): BluetoothSocket? {
    val socket = activeL2capSockets[address]

    if (socket == null) {
      return null
    }

    if (!socket.isConnected) {
      return null
    }

    return socket
  }

  fun removeSocket(address: String) {
    activeL2capSockets.remove(address)
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
            FlutterBlueLog.info("L2CAP RX | btaddr=$address, len=${data.size}, payload=$hexString")

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
        FlutterBlueLog.error("Disconnected from $address: ${e.message}")
        activeL2capSockets.remove(address)

        FlutterBlueDeviceManager.updateDevice(l2capState = "disconnected")
        BluetoothEventEmitter.emit("l2cap", "disconnected", address)
      }
    }
  }


  @RequiresApi(Build.VERSION_CODES.Q)
  fun openChannel(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val psm = call.argument<Int>("psm") ?: return result.error("INVALID_ARGUMENT", "Missing PSM parameter.", null)
    val secure = call.argument<Boolean>("secure") ?: false
    val timeout = call.argument<Int>("timeout") ?: 5000

    if (bluetoothAddress == null) {
      FlutterBlueLog.error("Cannot open L2CAP channel, reason: invalid bluetooth address")
      result.error("INVALID_ARGUMENT", "Cannot open L2CAP channel, reason: invalid bluetooth address", null)
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      FlutterBlueLog.error("Cannot open L2CAP channel, reason: Android version does not support")
      result.error("UNSUPPORTED", "Requires Android 10 or higher.", null)
      return
    }

    if (bluetoothAdapter?.isEnabled != true) {
      FlutterBlueLog.error("Cannot open L2CAP channel, reason: Bluetooth is not enabled.")
      result.error("BLUETOOTH_OFF", "Cannot open L2CAP channel, reason: Bluetooth is not enabled.", null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      FlutterBlueLog.error("Open l2cap failed, could not find device with address $bluetoothAddress")
      return
    }

    val manager = bluetoothManager ?: run {
      FlutterBlueLog.error("BluetoothManager is not initialized.")
      return
    }
    val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
    val isConnected = connectedDevices?.any { it.address == bluetoothAddress } == true

    if (!isConnected) {
      FlutterBlueLog.error("Device is not connected at GAP level")
      FlutterBlueLog.info("Device $bluetoothAddress is not connected at GAP level")
      return
    }

    result.success(true)

    val handler = Handler(Looper.getMainLooper())
    val timeoutRunnable = Runnable {
      activeL2capSockets[bluetoothAddress]?.close()
      activeL2capSockets.remove(bluetoothAddress)
      FlutterBlueLog.warn("L2CAP connection to $bluetoothAddress timed out.")
      FlutterBlueLog.error("L2CAP connection to $bluetoothAddress timed out after ${timeout}ms")
    }

    // Start timeout countdown
    handler.postDelayed(timeoutRunnable, timeout.toLong())
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

          FlutterBlueLog.info("L2CAP channel to $bluetoothAddress opened.")
        }

        listenL2capEvent(socket, bluetoothAddress)
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          handler.removeCallbacks(timeoutRunnable)
          l2capConnectionTimeouts.remove(bluetoothAddress)
          FlutterBlueLog.error(e.message ?: "Cannot open L2CAP channel")
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun closeChannel(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    if (bluetoothAddress == null) {
      FlutterBlueLog.error("Missing bluetoothAddress parameter.")
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    val socket = activeL2capSockets[bluetoothAddress]
    if (socket == null) {
      result.error("NOT_OPEN", "No active L2CAP channel for $bluetoothAddress", null)
      return
    }

    result.success(true)

    CoroutineScope(Dispatchers.IO).launch {
      try {
        FlutterBlueLog.info("Closing L2CAP channel for $bluetoothAddress")

        val hadListener = activeL2capSockets.containsKey(bluetoothAddress)
        socket.close()
        activeL2capSockets.remove(bluetoothAddress)

        withContext(Dispatchers.Main) {
          if (!hadListener) {
            // No listener, emit manually
            FlutterBlueDeviceManager.updateDevice(l2capState = "disconnected")
            BluetoothEventEmitter.emit("l2cap", "disconnected", bluetoothAddress)
          }

          FlutterBlueLog.info("L2CAP channel for $bluetoothAddress closed successfully.")
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          FlutterBlueLog.error("Failed to close L2CAP: ${e.message}")
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun sendData(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val data = call.argument<ByteArray>("data")

    if (bluetoothAddress == null || data == null) {
      FlutterBlueLog.error("Missing bluetoothAddress or data")
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress or data", null)
      return
    }

    val socket = activeL2capSockets[bluetoothAddress]
    if (socket == null) {
      FlutterBlueLog.error("No active L2CAP channel for $bluetoothAddress")
      return
    }

    result.success(true)

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val hexString = data.joinToString(" ") { "%02X".format(it) }

        FlutterBlueLog.info("L2CAP TX | btaddr=$bluetoothAddress, len=${data.size}, payload=$hexString")

        socket.outputStream.write(data)
        socket.outputStream.flush()

        withContext(Dispatchers.Main) {
          FlutterBlueLog.info("Sent ${data.size} bytes to $bluetoothAddress")
        }
      } catch (e: IOException) {
        withContext(Dispatchers.Main) {
          FlutterBlueLog.error("Failed to send data: ${e.message}")
        }
      }
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    bluetoothManager = binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun onDetachedFromEngine() {

  }
}
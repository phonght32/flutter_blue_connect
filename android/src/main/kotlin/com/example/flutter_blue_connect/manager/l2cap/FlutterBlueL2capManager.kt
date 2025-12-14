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
            FlutterBlueLog.info("L2CAP data received | btaddr=$address, len=${data.size}, data=$hexString")

            BluetoothEventEmitter.emit("l2cap", "dataReceived", address,
              mapOf(
                "data" to data,
                "hex" to hexString,
                "length" to data.size
              )
            )
          }
        }
      } catch (e: IOException) {
        FlutterBlueLog.error("L2CAP disconnected from $address: ${e.message}")
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
      val errorMsg = "Open L2CAP channel failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      val errorMsg = "Open L2CAP channel failed, reason: Android version does not support"
      FlutterBlueLog.error(errorMsg)
      result.error("UNSUPPORTED", errorMsg, null)
      return
    }

    if (bluetoothAdapter?.isEnabled != true) {
      val errorMsg = "Open L2CAP channel failed, reason: Bluetooth is not enabled"
      FlutterBlueLog.error(errorMsg)
      result.error("BLUETOOTH_OFF", errorMsg, null)
      return
    }

    val device = bluetoothAdapter?.getRemoteDevice(bluetoothAddress)
    if (device == null) {
      val errorMsg = "Open L2CAP channel failed, reason: could not find device address $bluetoothAddress"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
      return
    }

    val manager = bluetoothManager ?: run {
      val errorMsg = "Open L2CAP channel failed, reason: BluetoothManager is not initialized"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
      return
    }
    val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
    val isConnected = connectedDevices?.any { it.address == bluetoothAddress } == true

    if (!isConnected) {
      val errorMsg = "Open L2CAP channel failed, reason: Device is not connected at GAP level"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
      return
    }

    result.success(true)

    val handler = Handler(Looper.getMainLooper())
    val timeoutRunnable = Runnable {
      activeL2capSockets[bluetoothAddress]?.close()
      activeL2capSockets.remove(bluetoothAddress)
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

          FlutterBlueLog.error("Open L2CAP channel failed, message: ${e.message}")
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun closeChannel(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      val errorMsg = "L2CAP channel close failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val socket = activeL2capSockets[bluetoothAddress]
    if (socket == null) {
      val errorMsg = "L2CAP channel close failed, reason: No active L2CAP channel"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
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
            FlutterBlueDeviceManager.updateDevice(l2capState = "disconnected")
            BluetoothEventEmitter.emit("l2cap", "disconnected", bluetoothAddress)
          }

          FlutterBlueLog.info("L2CAP channel for $bluetoothAddress closed successfully.")
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          FlutterBlueLog.error("Close L2CAP channel failed, message: ${e.message}")
        }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun sendData(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")
    val data = call.argument<ByteArray>("data")

    if (bluetoothAddress == null) {
      val errorMsg = "L2CAP data send failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val socket = activeL2capSockets[bluetoothAddress]
    if (socket == null) {
      val errorMsg = "L2CAP data send failed, reason: No active L2CAP channel"
      FlutterBlueLog.error(errorMsg)
      result.error("NOT_EXIST", errorMsg, null)
      return
    }

    result.success(true)

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val hexString = data.joinToString(" ") { "%02X".format(it) }

        FlutterBlueLog.info("L2CAP data sent | btaddr=$bluetoothAddress, len=${data.size}, payload=$hexString")

        socket.outputStream.write(data)
        socket.outputStream.flush()

        withContext(Dispatchers.Main) {
          FlutterBlueLog.info("Sent ${data.size} bytes to $bluetoothAddress")
        }
      } catch (e: IOException) {
        withContext(Dispatchers.Main) {
          FlutterBlueLog.error("L2CAP data send failed, message: ${e.message}")
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
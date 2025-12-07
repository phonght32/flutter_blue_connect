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

  /**
   * Handles Flutter method calls for starting/stopping the Bluetooth scan.
   */
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {

      "startScan" -> FlutterBlueGapManager.startScan(call, result)
      "stopScan" -> FlutterBlueGapManager.stopScan(call, result)
      "connect" -> FlutterBlueGapManager.connect(call, result)
      "disconnect" -> FlutterBlueGapManager.disconnect(call, result)
      "deleteBond" -> FlutterBlueGapManager.deleteBond(call, result)

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
        FirmwareSupport.printAllBluetoothMethods()
      }

      "startPairing" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        FlutterBlueSmpManager.startPairing(bluetoothAddress)
      }

      "startPairingOob" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        val oobData = call.argument<ByteArray>("oobData")

        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        if (oobData == null) {
          result.error("INVALID_ARGUMENT", "Missing oob data.", null)
          return
        }

        FlutterBlueSmpManager.startPairingOob(bluetoothAddress, oobData)
      }

      "startChannelSounding" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        try {
          FlutterBlueChannelSoundingManager.start(bluetoothAddress)

          result.success("Channel sounding started for $bluetoothAddress")
        } catch (e: Exception) {
          result.error("START_FAILED", e.message, null)
        }
      }

      "stopChannelSounding" -> {
        val bluetoothAddress = call.argument<String>("bluetoothAddress")
        if (bluetoothAddress == null) {
          result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
          return
        }

        try {
          FlutterBlueChannelSoundingManager.stop(bluetoothAddress)
          result.success("Channel sounding stopped for $bluetoothAddress")
        } catch (e: Exception) {
          result.error("STOP_FAILED", e.message, null)
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

    // Start the encryption checker automatically
    FlutterBlueGapManager.onAttachedToEngine(binding)
    FlutterBlueL2capManager.onAttachedToEngine(binding)
    FlutterBlueSmpManager.onAttachedToEngine(binding)
    FlutterBlueChannelSoundingManager.onAttachedToEngine(binding)
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
    FlutterBlueSmpManager.onDetachedFromEngine()
    FlutterBlueChannelSoundingManager.onDetachedFromEngine()
  }
}
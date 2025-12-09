package com.example.flutter_blue_connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.embedding.engine.plugins.FlutterPlugin

import android.util.Log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.ranging.RangingManager
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingPreference
import android.ranging.RangingPreference.DEVICE_ROLE_INITIATOR
import android.ranging.SensorFusionParams
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.lang.Exception

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
object FlutterBlueChannelSoundingManager {

  private lateinit var appContext: Context

  private var rangingManager: RangingManager? = null
  private var rangingSession: RangingSession? = null
  private lateinit var rangingCapabilityCallback: RangingManager.RangingCapabilitiesCallback
  private var deviceAddress : String = ""

  /**
   * Checks if the app has the RANGING permission.
   * Requires Android version Baklava (API 36) or higher.
   *
   * @param context The context to use for checking permissions.
   * @return True if the RANGING permission is granted, false otherwise.
   */
  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun hasRangingPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RANGING
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun stopInternal(bluetoothAddress: String) {
    val session = rangingSession ?: return

    try {
      session.stop() // triggers onStopped/onClosed internally
      session.close() // safe to call immediately after stop
    } catch (e: Exception) {
      Log.e("ChannelSounding", "Error stopping/closing session: ${e.message}")
    }

    rangingSession = null

    try {
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    } catch (_: Exception) {}
  }

  // Implement all abstract methods of RangingSession.Callback
  private val rangingSessionCallback = @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  object : RangingSession.Callback {
    override fun onOpened() {
      BluetoothEventEmitter.emit(
        "gap",
        "channelSoundingOpened",
        deviceAddress,
      )
    }

    override fun onOpenFailed(reason: Int) {
      // Unregister the callback to avoid memory leaks
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    }

    override fun onStarted(
      peer: RangingDevice,
      technology: Int
    ) {
      BluetoothEventEmitter.emit(
        "gap",
        "channelSoundingStarted",
        deviceAddress,
      )
    }

    override fun onResults(
      peer: RangingDevice,
      data: RangingData
    ) {
      try {
        var measuredDistance : Float = 0.0f
        data.distance?.measurement?.toFloat()?.let { distance ->
          measuredDistance = distance
        }

        val bluetoothAddress = deviceAddress?: ""

        // Emit the channelSoundingDataReady event to Flutter
        BluetoothEventEmitter.emit(
          "gap",
          "channelSoundingDataReady",
          bluetoothAddress,
          mapOf(
            "dataChannelSounding" to measuredDistance
          )
        )

        Log.d("ChannelSounding", "Distance update emitted: $measuredDistance m for $bluetoothAddress")
      } catch (e: Exception) {
        Log.e("ChannelSounding", "Error emitting channel sounding data: ${e.message}")
      }
    }

    override fun onStopped(
      peer: RangingDevice,
      technology: Int
    ) {
      BluetoothEventEmitter.emit(
        "gap",
        "channelSoundingStopped",
        deviceAddress,
      )
    }

    override fun onClosed(reason: Int) {
      rangingSession = null
      try {
        rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
      } catch (_: Exception) {}
      BluetoothEventEmitter.emit(
        "gap",
        "channelSoundingClosed",
        deviceAddress,
      )

      deviceAddress = ""
    }
  }

  fun start(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    if (rangingManager == null) {
      result.error("NO_MANAGER", "RangingManager not initialized", null)
      return
    }

    if (rangingSession != null) {
      result.error("SESSION_EXISTS", "Ranging session already running", null)
      return
    }

    if (!hasRangingPermission(appContext)) {
      result.error("NO_PERMISSION", "Ranging permission not granted", null)
      return
    }

    deviceAddress = bluetoothAddress

    val setRangingUpdateRate = RawRangingDevice.UPDATE_RATE_NORMAL

    val rangingDevice = RangingDevice.Builder()
      .setUuid(java.util.UUID.nameUUIDFromBytes(bluetoothAddress.toByteArray()))
      .build()

    val csRangingParams = BleCsRangingParams
      .Builder(bluetoothAddress)
      .setRangingUpdateRate(setRangingUpdateRate)
      .setSecurityLevel(BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE)
      .build()

    val rawRangingDevice = RawRangingDevice.Builder()
      .setRangingDevice(rangingDevice)
      .setCsRangingParams(csRangingParams)
      .build()

    val rawRangingDeviceConfig = RawInitiatorRangingConfig.Builder()
      .addRawRangingDevice(rawRangingDevice)
      .build()

    val sensorFusionParams = SensorFusionParams.Builder()
      .setSensorFusionEnabled(false)
      .build()

    val sessionConfig = SessionConfig.Builder()
//      .setRangingMeasurementsLimit(65535)
      .setAngleOfArrivalNeeded(false)
      .setSensorFusionParams(sensorFusionParams)
      .build()

    val rangingPreference = RangingPreference.Builder(
      DEVICE_ROLE_INITIATOR,
      rawRangingDeviceConfig
    )
      .setSessionConfig(sessionConfig)
      .build()

    try {
      val manager = rangingManager ?: return result.error("NO_MANAGER", "RangingManager unavailable", null)

      rangingCapabilityCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
        if (capabilities.csCapabilities != null) {
          if (capabilities.csCapabilities!!.supportedSecurityLevels.contains(1)) {

            if (!hasRangingPermission(appContext)) {
              result.error("NO_PERMISSION", "Missing RANGING permission", null)
              return@RangingCapabilitiesCallback
            }

            try {
              rangingSession = manager.createRangingSession(
                appContext.mainExecutor,
                rangingSessionCallback
              )

              rangingSession?.let {
                it.addDeviceToRangingSession(rawRangingDeviceConfig)
                it.start(rangingPreference) // only start if addDevice succeeds
              }

              result.success(true)

            } catch (e:Exception) {
              result.error("START_FAILED", e.message, null)
            }

          } else {
            stopInternal(bluetoothAddress)
            result.error("NOT_SUPPORTED", "Device does not support Channel Sounding", null)
          }
        }
      }

      manager.registerCapabilitiesCallback(appContext.mainExecutor, rangingCapabilityCallback)
    } catch (e: Exception) {
      result.error("START_ERROR", e.message, null)
    }
  }

  /**
   * Closes the current ranging session if it exists.
   * Waits for the session to stop before closing it and unregistering the capabilities callback.
   * If onClosed is provided, it will be called after the session is closed.
   * Requires Android version Baklava (API 36) or higher.
   *
   * @param bluetoothAddress The address of the device associated with the ranging session.
   * @param onClosed An optional suspend function to be called after the session is closed.
   */
  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  fun stop(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      result.error("INVALID_ARGUMENT", "Missing bluetoothAddress parameter.", null)
      return
    }

    val session = rangingSession
    if (session == null) {
      result.error("NO_SESSION", "No active ranging session", null)
      return
    }

    try {
      session.stop() // triggers onStopped/onClosed internally
      session.close() // safe to call immediately after stop
      rangingSession = null

      try {
        rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
      } catch (_: Exception) {}

      result.success(true)

    } catch (e: Exception) {
      Log.e("ChannelSounding", "Error stopping/closing session: ${e.message}")
      result.error("STOP_FAILED", e.message, null)
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    appContext = binding.applicationContext

    rangingManager = appContext.getSystemService(RangingManager::class.java)
  }

  fun onDetachedFromEngine() {

  }
}

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
import android.ranging.RangingPreference.DEVICE_ROLE_RESPONDER
import android.ranging.SensorFusionParams
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawResponderRangingConfig
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

  private fun stopInitiatorInternal(bluetoothAddress: String) {
    val session = rangingSession ?: return

    try {
      session.stop() // triggers onStopped/onClosed internally
      session.close() // safe to call immediately after stop
    } catch (e: Exception) {
      FlutterBlueLog.error("Error stopping/closing channel sounding session, message: ${e.message}")
    }

    rangingSession = null

    try {
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    } catch (_: Exception) {}
  }

  private fun stopReflectorInternal(bluetoothAddress: String) {
    val session = rangingSession ?: return

    try {
      session.stop() // triggers onStopped/onClosed internally
      session.close() // safe to call immediately after stop
    } catch (e: Exception) {
      FlutterBlueLog.error("Error stopping/closing channel sounding session, message: ${e.message}")
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
      BluetoothEventEmitter.emit("gap", "channelSoundingOpened", deviceAddress)
    }

    override fun onOpenFailed(reason: Int) {
      // Unregister the callback to avoid memory leaks
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    }

    override fun onStarted(peer: RangingDevice, technology: Int) {
      BluetoothEventEmitter.emit("gap", "channelSoundingStarted", deviceAddress)
    }

    override fun onResults(peer: RangingDevice, data: RangingData) {
      try {
        var measuredDistance : Float = 0.0f
        data.distance?.measurement?.toFloat()?.let { distance ->
          measuredDistance = distance
        }

        val bluetoothAddress = deviceAddress?: ""

        // Emit the channelSoundingDataReady event to Flutter
        BluetoothEventEmitter.emit("gap", "channelSoundingDataReady", bluetoothAddress,
          mapOf(
            "dataChannelSounding" to measuredDistance
          )
        )

        FlutterBlueLog.info("Channel sounding data received with $bluetoothAddress, distance (m): $measuredDistance")
      } catch (e: Exception) {
        FlutterBlueLog.error("Error on get channel sounding data, message: ${e.message}")
      }
    }

    override fun onStopped(peer: RangingDevice, technology: Int) {
      BluetoothEventEmitter.emit("gap", "channelSoundingStopped", deviceAddress)
    }

    override fun onClosed(reason: Int) {
      rangingSession = null
      deviceAddress = ""

      try {
        rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
      } catch (_: Exception) {}

      BluetoothEventEmitter.emit("gap", "channelSoundingClosed", deviceAddress)
    }
  }

  fun startInitiator(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      val errorMsg = "Start channel sounding initiator failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    if (rangingManager == null) {
      val errorMsg = "Start channel sounding initiator failed, reason: RangingManager not initialized"
      FlutterBlueLog.error(errorMsg)
      result.error("MANAGER_NULL", errorMsg, null)
      return
    }

    if (rangingSession != null) {
      val errorMsg = "Start channel sounding initiator failed, reason: Ranging session already running"
      FlutterBlueLog.error(errorMsg)
      result.error("SESSION_EXIST", errorMsg, null)
      return
    }

    if (!hasRangingPermission(appContext)) {
      val errorMsg = "Start channel sounding initiator failed, reason: Ranging permission not granted"
      FlutterBlueLog.error(errorMsg)
      result.error("NO_PERMISSION", errorMsg, null)
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
              val errorMsg = "Ranging capability callback failed, reason: Ranging permission not granted"
              FlutterBlueLog.error(errorMsg)
              result.error("NO_PERMISSION", errorMsg, null)
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
              val errorMsg = "Ranging capability callback failed, message: ${e.message}"
              FlutterBlueLog.error(errorMsg)
              result.error("START_FAILED", errorMsg, null)
            }

          } else {
            stopInitiatorInternal(bluetoothAddress)
            val errorMsg = "Ranging capability callback failed, reason: Device does not support Channel Sounding"
            FlutterBlueLog.error(errorMsg)
            result.error("NOT_SUPPORTED", errorMsg, null)
          }
        }
      }

      manager.registerCapabilitiesCallback(appContext.mainExecutor, rangingCapabilityCallback)
    } catch (e: Exception) {
      val errorMsg = "Start channel sounding initiator failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("START_FAILED", errorMsg, null)
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
  fun stopInitiator(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      val errorMsg = "Stop channel sounding initiator failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val session = rangingSession
    if (session == null) {
      result.success(false)
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
      val errorMsg = "Stop channel sounding initiator failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("STOP_FAILED", errorMsg, null)
    }
  }

  fun startReflector(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      val errorMsg = "Start channel sounding reflector failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    if (rangingManager == null) {
      val errorMsg = "Start channel sounding reflector failed, reason: RangingManager not initialized"
      FlutterBlueLog.error(errorMsg)
      result.error("MANAGER_NULL", errorMsg, null)
      return
    }

    if (rangingSession != null) {
      val errorMsg = "Start channel sounding reflector failed, reason: Ranging session already running"
      FlutterBlueLog.error(errorMsg)
      result.error("SESSION_EXIST", errorMsg, null)
      return
    }

    if (!hasRangingPermission(appContext)) {
      val errorMsg = "Start channel sounding reflector failed, reason: Ranging permission not granted"
      FlutterBlueLog.error(errorMsg)
      result.error("NO_PERMISSION", errorMsg, null)
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

    val rawRangingDeviceConfig = RawResponderRangingConfig.Builder()
      .setRawRangingDevice(rawRangingDevice)
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
      DEVICE_ROLE_RESPONDER,
      rawRangingDeviceConfig
    )
      .setSessionConfig(sessionConfig)
      .build()

    try {
      val manager = rangingManager
      if (manager == null) {
        val errorMsg = "Start channel sounding reflector failed, reason: RangingManager not initialized"
        FlutterBlueLog.error(errorMsg)
        result.error("MANAGER_NULL", errorMsg, null)
        return
      }

      rangingCapabilityCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
        if (capabilities.csCapabilities != null) {
          if (capabilities.csCapabilities!!.supportedSecurityLevels.contains(1)) {

            if (!hasRangingPermission(appContext)) {
              val errorMsg = "Ranging capability callback failed, reason: Ranging permission not granted"
              FlutterBlueLog.error(errorMsg)
              result.error("NO_PERMISSION", errorMsg, null)
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
              val errorMsg = "Ranging capability callback failed, message: ${e.message}"
              FlutterBlueLog.error(errorMsg)
              result.error("START_FAILED", errorMsg, null)
            }

          } else {
            stopReflectorInternal(bluetoothAddress)
            val errorMsg = "Ranging capability callback failed, reason: Device does not support Channel Sounding"
            FlutterBlueLog.error(errorMsg)
            result.error("NOT_SUPPORTED", errorMsg, null)
          }
        }
      }

      manager.registerCapabilitiesCallback(appContext.mainExecutor, rangingCapabilityCallback)
    } catch (e: Exception) {
      val errorMsg = "Start channel sounding reflector failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("START_FAILED", errorMsg, null)
    }
  }

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  fun stopReflector(call: MethodCall, result: MethodChannel.Result) {
    val bluetoothAddress = call.argument<String>("bluetoothAddress")

    if (bluetoothAddress == null) {
      val errorMsg = "Stop channel sounding reflector failed, reason: missing address"
      FlutterBlueLog.error(errorMsg)
      result.error("INVALID_ARGUMENT", errorMsg, null)
      return
    }

    val session = rangingSession
    if (session == null) {
      result.success(false)
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
      val errorMsg = "Stop channel sounding reflector failed, message: ${e.message}"
      FlutterBlueLog.error(errorMsg)
      result.error("STOP_FAILED", errorMsg, null)
    }
  }

  fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    appContext = binding.applicationContext

    rangingManager = appContext.getSystemService(RangingManager::class.java)
  }

  fun onDetachedFromEngine() {

  }
}

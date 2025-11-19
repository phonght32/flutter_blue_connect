package com.example.flutter_blue_connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

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

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class ChannelSoundingManager(private val context: Context) {

  private val rangingManager: RangingManager? = context.getSystemService(RangingManager::class.java)
  private var rangingSession: RangingSession? = null
  private lateinit var rangingCapabilityCallback: RangingManager.RangingCapabilitiesCallback
  private var deviceAddress : String? = null

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

  /**
   * Clears the data associated with the given device ID.
   * This removes the entry from the internal map, effectively resetting the state for that device.
   *
   * @param deviceId The ID of the device whose data should be cleared.
   */
  fun clear(bluetoothAddress: String) {

  }

  // Implement all abstract methods of RangingSession.Callback
  private val rangingSessionCallback = @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  object : RangingSession.Callback {
    override fun onClosed(reason: Int) {
      // Unregister the callback to avoid memory leaks
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    }

    override fun onOpenFailed(reason: Int) {
      // Unregister the callback to avoid memory leaks
      rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
    }

    override fun onOpened() {

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

        val bluetoothAddress = deviceAddress?: return

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


    override fun onStarted(
      peer: RangingDevice,
      technology: Int
    ) {

    }

    override fun onStopped(
      peer: RangingDevice,
      technology: Int
    ) {

    }
  }

  fun startChannelSounding(bluetoothAddress: String) {
    if (rangingManager == null || rangingSession !=null || !hasRangingPermission(context)) return

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
      .setSensorFusionEnabled(true)
      .build()

    val sessionConfig = SessionConfig.Builder()
      .setRangingMeasurementsLimit(1000)
      .setAngleOfArrivalNeeded(true)
      .setSensorFusionParams(sensorFusionParams)
      .build()

    val rangingPreference = RangingPreference.Builder(
      DEVICE_ROLE_INITIATOR,
      rawRangingDeviceConfig
    )
      .setSessionConfig(sessionConfig)
      .build()

    rangingCapabilityCallback = RangingManager.RangingCapabilitiesCallback { capabilities ->
      if (capabilities.csCapabilities != null) {
        if (capabilities.csCapabilities!!.supportedSecurityLevels.contains(1)) {
          // Channel Sounding supported
          // Check if Ranging Permission is granted before starting the session
          if (hasRangingPermission(context)) {
            rangingSession = rangingManager.createRangingSession(
              context.mainExecutor,
              rangingSessionCallback
            )
            rangingSession?.let {
              try {
                it.addDeviceToRangingSession(rawRangingDeviceConfig)
              } catch (e: Exception) {

              } finally {
                it.start(rangingPreference)
              }
            } ?: run {

              return@RangingCapabilitiesCallback
            }
          } else {

            return@RangingCapabilitiesCallback
          }
        } else {
          stopChannelSounding(bluetoothAddress)
        }
      } else {
        stopChannelSounding(bluetoothAddress)
      }

    }

    rangingManager.registerCapabilitiesCallback(context.mainExecutor, rangingCapabilityCallback!!)
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
  fun stopChannelSounding(
    bluetoothAddress: String,
    onClosed: (suspend () -> Unit)? = null
  ) {
    val session = rangingSession ?: return

    deviceAddress = null

    CoroutineScope(Dispatchers.IO).launch {
      try {
        session.stop()
        // Wait for onStopped() or onClosed() before closing
        delay(1000) // Give the system time to propagate onStopped
        withContext(Dispatchers.Main) {
          session.close()
          rangingSession = null
          rangingManager?.unregisterCapabilitiesCallback(rangingCapabilityCallback)
          delay(1500)
          onClosed?.let { it() } ?: run {
            clear(bluetoothAddress)
          }
        }
      } catch (e: Exception) {

      }
    }
  }

}

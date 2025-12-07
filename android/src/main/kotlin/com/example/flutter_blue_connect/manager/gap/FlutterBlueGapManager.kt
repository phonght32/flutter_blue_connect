package com.example.flutter_blue_connect

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

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

import java.util.concurrent.ConcurrentHashMap

import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.embedding.engine.plugins.FlutterPlugin

object FlutterBlueGapManager {

	private lateinit var appContext: Context

	// Bluetooth adapter reference
	private var bluetoothAdapter: BluetoothAdapter? = null
	private var bluetoothManager: BluetoothManager? = null

	private var scanResultSink: EventChannel.EventSink? = null
	private lateinit var scanChannel: EventChannel

	data class ScannedDevice(
		val device: BluetoothDevice,
		val advData: List<Int>,
		val rssi: Int,
		val timestamp: Long
	)

	val activeGattConnections = ConcurrentHashMap<String, BluetoothGatt>()

	// Maps to store scanned Bluetooth devices with their timestamps
	private val mapScannedBluetoothDevice = ConcurrentHashMap<String, ScannedDevice>()
	private var mapPreviousScannedBluetoothDevice = emptyMap<String, ScannedDevice>()

	private val pendingConnectionResults = mutableMapOf<String, MethodChannel.Result>()
	private val linkLayerConnectionTimeouts = mutableMapOf<String, Pair<Handler, Runnable>>()

	private var scanRefreshTimeMs: Int = 500

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

				FlutterBlueLog.info("Connected to device $address")
				pendingResult?.success("Connected to device $address")

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

				FlutterBlueLog.info("Disconnected with device $address")
				pendingResult?.error("CONNECTION_FAILED", "Disconnected with device $address", null)

				BluetoothEventEmitter.emit("gap", "disconnected", address)

				FlutterBlueDeviceManager.clear()
			}
		}

		override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				FlutterBlueLog.info("Services discovered: ${gatt.services}")

				BluetoothEventEmitter.emit("gatt", "servicesDiscovered", gatt.device.address, mapOf(
					"services" to gatt.services.map { it.uuid.toString() }
				))
			}
		}

		override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				FlutterBlueLog.info("Read characteristic: ${characteristic.uuid}, value: ${characteristic.value}")

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

	fun startScan(call: MethodCall, result: MethodChannel.Result) {
		scanRefreshTimeMs = call.argument<Int>("refreshTimeMs") ?: 500

		val scanner = bluetoothAdapter?.bluetoothLeScanner
		val scanFilters = listOf<ScanFilter>()  // Apply filters if needed
		val settings = ScanSettings.Builder()
			.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
			.build()
		scanner?.startScan(scanFilters, settings, scanCallback)

		handlerScanResultChangedCheck.post(runnableScanResultChangedCheck)
		handlerTimerCleanup.postDelayed(runnableTimerCleanup, 1000)

		FlutterBlueLog.info("Bluetooth scanning started.")
		result.success(true)
	}

	fun stopScan(call: MethodCall, result: MethodChannel.Result) {
		val scanner = bluetoothAdapter?.bluetoothLeScanner
		scanner?.stopScan(scanCallback)
		handlerScanResultChangedCheck.removeCallbacks(runnableScanResultChangedCheck)
		handlerTimerCleanup.removeCallbacks(runnableTimerCleanup) // Stop the cleanup loop

		FlutterBlueLog.info("Bluetooth scanning stopped.")
		result.success(true)
	}

	fun connect(call: MethodCall, result: MethodChannel.Result) {
		val bluetoothAddress = call.argument<String>("bluetoothAddress")
		val timeoutMillis = call.argument<Int>("timeout") ?: 10000

		if (bluetoothAdapter?.isEnabled != true) {
			FlutterBlueLog.error("Cannot connect to target, reason: BLUETOOTH_DISABLED")
			result.error("BLUETOOTH_DISABLED", "Cannot connect to target - Bluetooth is disabled", null)
			return
		}

		if (bluetoothAddress == null) {
			FlutterBlueLog.error("Cannot connect to target, reason: INVALID_ADDRESS")
			result.error("INVALID_ADDRESS", "Cannot connect to target - Invalid address", null)
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
			FlutterBlueLog.error("Cannot connect to target, reason: GATT_NULL")
			result.error("GATT_NULL", "Cannot connect to target - GATT NULL", null)
		}
	}

	fun disconnect(call: MethodCall, result: MethodChannel.Result) {
		val bluetoothAddress = call.argument<String>("bluetoothAddress")

		if (bluetoothAddress == null) {
			FlutterBlueLog.error("Cannot disconnect to target, reason: INVALID_ADDRESS")
			result.error("INVALID_ARGUMENT", "Cannot disconnect to target - Invalid address", null)
			return
		}

		val gatt = activeGattConnections[bluetoothAddress]
		if (gatt == null) {
			FlutterBlueLog.error("Cannot disconnect, reason: NO_CONNECTION")
			result.error("NO_CONNECTION", "No active GATT connection", null)
			return
		}

		FlutterBlueL2capManager.closeChannel(bluetoothAddress, false)

		// just disconnect, don't close yet — let callback handle it
		gatt.disconnect()

		// Optional: close later after delay
		Handler(Looper.getMainLooper()).postDelayed({
			gatt.close()
			activeGattConnections.remove(bluetoothAddress)
			result.success(true)
		}, 500)

		FlutterBlueLog.info("Disconnecting from $bluetoothAddress ...")
	}

	fun deleteBond(call: MethodCall, result: MethodChannel.Result) {
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
				FlutterBlueLog.info("removeBond($bluetoothAddress): success")
				result.success(true)
			} else {
				FlutterBlueLog.warn("removeBond($bluetoothAddress): failed")
				result.error("BOND_REMOVE_FAILED", "removeBond returned false", null)
			}
		} catch (e: Exception) {
			FlutterBlueLog.error("Error removing bond for $bluetoothAddress, msg: $e")
			result.error("REMOVE_EXCEPTION", e.message, null)
		}
	}

	fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
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

		val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
		appContext.registerReceiver(bondStateReceiver, filter)

		BluetoothEncryptionMonitor.start()
	}

	fun onDetachedFromEngine() {
		scanResultSink = null
		handlerScanResultChangedCheck.removeCallbacks(runnableScanResultChangedCheck)
		handlerTimerCleanup.removeCallbacks(runnableTimerCleanup)

		BluetoothEncryptionMonitor.stop()

		try {
			appContext.unregisterReceiver(bondStateReceiver)
		} catch (e: IllegalArgumentException) {
			Log.w("FlutterBlueConnect", "Receiver already unregistered: ${e.message}")
		}
	}
}
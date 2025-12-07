package com.example.flutter_blue_connect

import android.os.Build

object FirmwareSupport {
  fun printAllBluetoothMethods() {
//    try {
//      val sb = StringBuilder()
//
//      // ========== BluetoothAdapter Methods ==========
//      sb.append("\n========== BluetoothAdapter ALL Methods ==========\n")
//
//      bluetoothAdapter?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
//        sb.append("${method.name}(")
//        method.parameterTypes.forEachIndexed { index, param ->
//          sb.append(param.simpleName)
//          if (index < method.parameterTypes.size - 1) sb.append(", ")
//        }
//        sb.append("): ${method.returnType.simpleName}\n")
//      }
//
//      // ========== BluetoothDevice Methods ==========
//      sb.append("\n========== BluetoothDevice ALL Methods ==========\n")
//
//      val sampleDevice = bluetoothAdapter?.bondedDevices?.firstOrNull()
//        ?: bluetoothAdapter?.getRemoteDevice("00:00:00:00:00:00")
//
//      sampleDevice?.javaClass?.methods?.sortedBy { it.name }?.forEach { method ->
//        sb.append("${method.name}(")
//        method.parameterTypes.forEachIndexed { index, param ->
//          sb.append(param.simpleName)
//          if (index < method.parameterTypes.size - 1) sb.append(", ")
//        }
//        sb.append("): ${method.returnType.simpleName}\n")
//      }
//
//      // ========== OOB-Related Methods Only ==========
//      sb.append("\n========== OOB-Related Methods ==========\n")
//      sb.append("--- BluetoothAdapter ---\n")
//
//      bluetoothAdapter?.javaClass?.methods?.filter {
//        it.name.contains("oob", ignoreCase = true) ||
//          it.name.contains("pairing", ignoreCase = true) ||
//          it.name.contains("bond", ignoreCase = true)
//      }?.forEach { method ->
//        sb.append("${method.name}(")
//        method.parameterTypes.forEachIndexed { index, param ->
//          sb.append(param.simpleName)
//          if (index < method.parameterTypes.size - 1) sb.append(", ")
//        }
//        sb.append("): ${method.returnType.simpleName}\n")
//      }
//
//      sb.append("\n--- BluetoothDevice ---\n")
//      sampleDevice?.javaClass?.methods?.filter {
//        it.name.contains("oob", ignoreCase = true) ||
//          it.name.contains("pairing", ignoreCase = true) ||
//          it.name.contains("bond", ignoreCase = true)
//      }?.forEach { method ->
//        sb.append("${method.name}(")
//        method.parameterTypes.forEachIndexed { index, param ->
//          sb.append(param.simpleName)
//          if (index < method.parameterTypes.size - 1) sb.append(", ")
//        }
//        sb.append("): ${method.returnType.simpleName}\n")
//      }
//
//      // ========== Check for OobData class ==========
//      sb.append("\n========== OobData Class Check ==========\n")
//      try {
//        val oobDataClass = Class.forName("android.bluetooth.OobData")
//        sb.append("✅ OobData class EXISTS\n")
//        sb.append("Constructors:\n")
//        oobDataClass.constructors.forEach { constructor ->
//          sb.append("  OobData(")
//          constructor.parameterTypes.forEachIndexed { index, param ->
//            sb.append(param.simpleName)
//            if (index < constructor.parameterTypes.size - 1) sb.append(", ")
//          }
//          sb.append(")\n")
//        }
//        sb.append("Methods:\n")
//        oobDataClass.methods.sortedBy { it.name }.forEach { method ->
//          sb.append("  ${method.name}(")
//          method.parameterTypes.forEachIndexed { index, param ->
//            sb.append(param.simpleName)
//            if (index < method.parameterTypes.size - 1) sb.append(", ")
//          }
//          sb.append("): ${method.returnType.simpleName}\n")
//        }
//      } catch (e: ClassNotFoundException) {
//        sb.append("❌ OobData class NOT FOUND\n")
//      }
//
//      // ========== Check Android Version Info ==========
//      sb.append("\n========== Device Info ==========\n")
//      sb.append("Android Version: ${Build.VERSION.RELEASE}\n")
//      sb.append("SDK Int: ${Build.VERSION.SDK_INT}\n")
//      sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
//
//      sb.append("\n==============================================\n")
//
//      val output = sb.toString()
//      logMessage("info", output)
//
//      result.success(mapOf(
//        "methods" to output
//      ))
//
//    } catch (e: Exception) {
//      logMessage("error", "Failed to print methods: ${e.message}")
//      result.error("PRINT_METHODS_ERROR", e.message, null)
//    }
  }
}
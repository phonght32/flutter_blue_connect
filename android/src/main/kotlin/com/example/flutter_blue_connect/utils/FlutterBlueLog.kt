package com.example.flutter_blue_connect

import android.util.Log

object FlutterBlueLog {

  private fun repareMsg(message: String): String {
    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
      .format(java.util.Date())
    val logMsg = "[$ts] $message"

    return logMsg
  }


  fun info (message: String) {
    val logMsg = repareMsg(message)
    Log.i("FlutterBlueConnect", logMsg)
  }

  fun warn (message: String) {
    val logMsg = repareMsg(message)
    Log.w("FlutterBlueConnect", logMsg)
  }

  fun error (message: String) {
    val logMsg = repareMsg(message)
    Log.e("FlutterBlueConnect", logMsg)
  }

  fun debug (message: String) {
    val logMsg = repareMsg(message)
    Log.d("FlutterBlueConnect", logMsg)
  }
}
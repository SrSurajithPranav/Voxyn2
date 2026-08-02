package com.voxyn.utils

import android.util.Log

object VoxynLogger {
    private const val DEFAULT_TAG = "VOXYN"

    fun d(message: String, tag: String = DEFAULT_TAG) = Log.d(tag, message)
    fun i(message: String, tag: String = DEFAULT_TAG) = Log.i(tag, message)
    fun w(message: String, tag: String = DEFAULT_TAG) = Log.w(tag, message)
    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) =
        Log.e(tag, message, throwable)
    fun engine(message: String) = Log.i("VOXYN::ENGINE", message)
    fun phase(phase: String, message: String) = Log.d("VOXYN::$phase", message)
}

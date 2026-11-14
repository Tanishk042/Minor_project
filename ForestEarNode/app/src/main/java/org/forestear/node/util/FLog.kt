package org.forestear.node.util

import android.util.Log

object FLog {
    private const val TAG = "ForestEar"
    var verbose = true

    fun d(msg: String) { if (verbose) Log.d(TAG, msg) }
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String) { Log.w(TAG, msg) }
    fun e(msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(TAG, msg) else Log.e(TAG, msg, tr)
    }
}

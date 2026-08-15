package com.amarhelper.console.core.log

import android.util.Log
import com.amarhelper.console.BuildConfig

/**
 * Thin logging facade.
 *
 * DEBUG and INFO are compiled in but suppressed unless [BuildConfig.VERBOSE_LOGGING] is set,
 * which is true only for debug builds. WARN and ERROR always emit.
 *
 * Nothing here ever receives a token: credentials are redacted at the boundary
 * (see [com.amarhelper.console.data.net.RedactingLogger]) and never passed to this class.
 */
object AppLogger {

    private const val MAX_TAG_LENGTH = 23

    fun d(tag: String, message: String) {
        if (BuildConfig.VERBOSE_LOGGING) Log.d(tag.safe(), message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.VERBOSE_LOGGING) Log.i(tag.safe(), message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag.safe(), message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag.safe(), message, throwable)
    }

    private fun String.safe(): String = if (length <= MAX_TAG_LENGTH) this else take(MAX_TAG_LENGTH)
}

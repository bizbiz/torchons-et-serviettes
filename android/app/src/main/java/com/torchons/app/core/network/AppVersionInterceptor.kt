package com.torchons.app.core.network

import com.torchons.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Declares this build's version to the backend, and reacts to its refusal.
 *
 * One interceptor owns both halves on purpose: sending `X-App-Version-Code` and
 * reading the 426 that answers it are the two ends of a single protocol, and
 * splitting them across two classes would let one be wired without the other.
 *
 * `versionCode` is sent, never `versionName`: it is a monotonic integer, while
 * "0.9.0" sorts above "0.10.0" compared as a string.
 *
 * The refusal that matters is the SERVER's -- this class only surfaces it. A
 * check living solely in the app would be trivially removed from the very APK
 * one wants to stop; see `backend/app/utils/version_gate.py`.
 */
@Singleton
class AppVersionInterceptor @Inject constructor(
    private val monitor: VersionGateMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(
            request.newBuilder()
                .header(VERSION_HEADER, BuildConfig.VERSION_CODE.toString())
                .build()
        )

        if (response.code == HTTP_UPGRADE_REQUIRED) {
            readGate(response)
        }
        return response
    }

    /**
     * Reads the gate payload without consuming the body for the caller.
     *
     * `peekBody` and not `body.string()`: the latter closes the stream, and the
     * call further up would then fail on an empty body -- turning a clear
     * "update required" into an opaque parse error.
     */
    private fun readGate(response: Response) {
        val raw = try {
            response.peekBody(PEEK_LIMIT_BYTES).string()
        } catch (_: Exception) {
            return
        }
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            // A 426 we cannot parse is not a reason to fence the user out: it
            // may not even come from our backend (a proxy, a captive portal).
            return
        }
        val min = json.optInt("min_version_code", 0)
        if (min <= 0) return
        monitor.reportGateClosed(
            minVersionCode = min,
            downloadUrl = json.optString("download_url", ""),
        )
    }

    private companion object {
        const val VERSION_HEADER = "X-App-Version-Code"
        const val HTTP_UPGRADE_REQUIRED = 426
        const val PEEK_LIMIT_BYTES = 4096L
    }
}

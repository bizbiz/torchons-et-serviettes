package com.torchons.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the server's "this device is revoked" answer into a local wipe.
 *
 * Modelled on [AppVersionInterceptor], which does the same job for the 426 of the
 * version gate: one interceptor owns both ends of one protocol, so neither half can
 * be wired without the other.
 *
 * The discrimination is the whole point. A 401 is ordinary -- an expired token, an
 * auth outage, a clock skew -- and must erase NOTHING. Only a 401 whose body carries
 * `"code": "DEVICE_REVOKED"` means the access was deliberately cut, and only that one
 * wipes. Keying on the human message would be worse than fragile: it is localized, so
 * the wipe would depend on the phone's language.
 *
 * Rationale: docs/appareils.fr.md
 */
@Singleton
class DeviceRevocationInterceptor @Inject constructor(
    private val monitor: DeviceRevocationMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == HTTP_UNAUTHORIZED) {
            readRevocation(response)
        }
        return response
    }

    /**
     * Reads the error code without consuming the body for the caller.
     *
     * `peekBody`, never `body.string()`: the latter closes the stream and the call
     * further up would fail on an empty body, turning a clear refusal into an opaque
     * parse error.
     */
    private fun readRevocation(response: Response) {
        val raw = try {
            response.peekBody(PEEK_LIMIT_BYTES).string()
        } catch (_: Exception) {
            return
        }
        val code = try {
            JSONObject(raw).optString("code", "")
        } catch (_: Exception) {
            // A 401 we cannot parse is an ordinary 401. Never wipe on a guess:
            // it could come from a proxy or a captive portal, not from us.
            return
        }
        if (code == REVOKED_CODE) {
            monitor.onRevoked()
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val REVOKED_CODE = "DEVICE_REVOKED"
        const val PEEK_LIMIT_BYTES = 4096L
    }
}

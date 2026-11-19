package com.torchons.app.ui.common

import com.torchons.app.data.api.ApiHttpException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Scope: the pure translator `UserErrors.translate` and `ApiHttpException`
 * (id extraction). Invariant: an exception's raw message never reaches the
 * rendered text. Paths by which it could leak:
 *  - IOException family, directly or as a cause: covered.
 *  - HTTP failure with header id / body-only id / no id / malformed id: covered.
 *  - HTTP status the screen names (`known`): covered (message comes from the screen).
 *  - local exception (ISE, NPE, IOOBE): covered.
 * non couvert : `UserErrors.from`, Context + Log Android non testables en JVM
 *   (il enrobe `translate` avec les chaines de ressources).
 * non couvert : ecrans qui fabriquent encore leur propre texte, controles par
 *   le grep de `e.message` du rapport, pas par ce test.
 */
class UserErrorsTest {

    private object Texts : UserErrorTexts {
        override fun unreachable() = "UNREACHABLE"
        override fun serverWithCode(errorId: String) = "CODE $errorId"
        override fun serverWithStatus(status: Int) = "STATUS $status"
        override fun unexpected() = "UNEXPECTED"
    }

    private fun failedResponse(status: Int, body: String, errorIdHeader: String? = null): Response<Any> {
        val raw = okhttp3.Response.Builder()
            .code(status)
            .message("err")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://10.9.8.7:8002/x").build())
            .apply { if (errorIdHeader != null) header("X-Error-Id", errorIdHeader) }
            .build()
        return Response.error(body.toResponseBody(null), raw)
    }

    @Test fun noContactCasesAreUnreachableWithButtonAndNoCode() {
        val raws = listOf(
            IOException("failed to connect to /10.9.8.7 (port 8002)"),
            ConnectException("Failed to connect to /10.9.8.7:8002"),
            SocketTimeoutException("timeout 10.9.8.7"),
            UnknownHostException("api.example.test"),
            SSLHandshakeException("TLS 10.9.8.7"),
        )
        for (e in raws) {
            val r = UserErrors.translate(e, Texts)
            assertEquals("UNREACHABLE", r.message)
            assertNull(r.errorId)
            assertTrue(r.reportable)
            assertFalse(r.message.contains("10.9.8.7"))
        }
    }

    @Test fun ioExceptionWrappedAsCauseIsStillUnreachable() {
        val r = UserErrors.translate(RuntimeException("wrap", IOException("10.9.8.7")), Texts)
        assertEquals("UNREACHABLE", r.message)
    }

    @Test fun httpWithHeaderShowsCode() {
        val ex = ApiHttpException.from(failedResponse(500, "{\"detail\":\"internal_error\"}", "E-7K3QX"))
        val r = UserErrors.translate(ex, Texts)
        assertEquals("CODE E-7K3QX", r.message)
        assertEquals("E-7K3QX", r.errorId)
        assertTrue(r.reportable)
    }

    @Test fun httpWithErrorIdInBodyOnlyShowsCode() {
        val ex = ApiHttpException.from(
            failedResponse(500, "{\"detail\":\"internal_error\",\"error_id\":\"E-7K3QX\"}"),
        )
        assertEquals("E-7K3QX", ex.errorId)
        assertEquals("CODE E-7K3QX", UserErrors.translate(ex, Texts).message)
    }

    @Test fun headerWinsOverBody() {
        val ex = ApiHttpException.from(failedResponse(500, "{\"error_id\":\"E-AAAAA\"}", "E-BBBBB"))
        assertEquals("E-BBBBB", ex.errorId)
    }

    @Test fun httpWithoutIdShowsStatus() {
        val ex = ApiHttpException.from(failedResponse(500, "<html>boom 10.9.8.7</html>"))
        assertNull(ex.errorId)
        val r = UserErrors.translate(ex, Texts)
        assertEquals("STATUS 500", r.message)
        assertTrue(r.reportable)
    }

    @Test fun malformedIdIsIgnored() {
        // 'O', '0', 'I', 'L', 'N', 'U' are not in the alphabet; wrong length either.
        for (bad in listOf("E-7K3Q", "E-7K3QXX", "E-0K3QX", "e-7K3QX", "E-7K3QU", "<script>")) {
            val ex = ApiHttpException.from(failedResponse(500, "{}", bad))
            assertNull(bad, ex.errorId)
        }
    }

    @Test fun namedStatusKeepsTheReadableMessageWithoutButton() {
        val ex = ApiHttpException.from(failedResponse(410, "{\"detail\":\"expired\"}", "E-7K3QX"))
        val r = UserErrors.translate(ex, Texts) { if (it.httpCode == 410) "Invitation expired" else null }
        assertEquals("Invitation expired", r.message)
        assertFalse(r.reportable)
    }

    @Test fun unnamedStatusFallsThroughEvenWhenScreenNamesOthers() {
        val ex = ApiHttpException.from(failedResponse(503, "", null))
        val r = UserErrors.translate(ex, Texts) { if (it.httpCode == 410) "Invitation expired" else null }
        assertEquals("STATUS 503", r.message)
    }

    @Test fun detailKeyIsExposed() {
        assertEquals("invite_expired", ApiHttpException(410, "{\"detail\":\"invite_expired\"}").detail)
        assertNull(ApiHttpException(500, "not json").detail)
    }

    @Test fun localExceptionIsUnexpectedAndNeverLeaksItsMessage() {
        val secret = "NullPointerException at com.torchons.Secret.kt:42 10.9.8.7"
        val locals = listOf(
            IllegalStateException(secret),
            NullPointerException(secret),
            IndexOutOfBoundsException(secret),
        )
        for (e in locals) {
            val r = UserErrors.translate(e, Texts)
            assertEquals("UNEXPECTED", r.message)
            assertTrue(r.reportable)
            assertNull(r.errorId)
            assertFalse(r.message.contains("Secret"))
            assertFalse(r.message.contains("10.9.8.7"))
        }
    }

    @Test fun rawMessageNeverAppearsInAnyRenderedText() {
        val raw = "RAW-MESSAGE-10.9.8.7"
        val throwables = listOf(
            IOException(raw),
            IllegalStateException(raw),
            ApiHttpException(500, raw),
            ApiHttpException(404, raw, "E-7K3QX"),
        )
        for (t in throwables) {
            val r = UserErrors.translate(t, Texts)
            assertFalse("${t::class.simpleName} leaked", r.message.contains("RAW-MESSAGE"))
            assertFalse(r.message.contains("10.9.8.7"))
        }
    }
}

package com.torchons.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The join screen accepts a pasted invite: either the bare code or the whole
 * message shared from the invite screen (WhatsApp, SMS...). The token is
 * `secrets.token_hex(16)` server side, 32 lowercase hex chars.
 *
 * PATHS by which an invite can reach `setInviteToken`, listed before the cases:
 *   (1) paste field -> extractInviteToken ................................ COVERED
 *   (2) QR scan (raw value passed as is) ..... not covered here: camera result,
 *       the QR encodes the bare token; needs a device.
 *   (3) a token the server no longer knows (expired, used) ... not covered here:
 *       /pairing/info answers 404/409/410 and the existing snackbar shows it.
 */
class InviteTokenExtractTest {

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun bareCode() = assertEquals(token, extractInviteToken(token))

    @Test
    fun bareCodeWithSpacesAndUppercase() =
        assertEquals(token, extractInviteToken("  ${token.uppercase()}\n"))

    @Test
    fun wholeSharedMessage() = assertEquals(
        token,
        extractInviteToken(
            "Rejoins le foyer « Maison » sur Torchons & Serviettes. Dans l'app, choisis " +
                "« Rejoindre un foyer » et colle ce message. Le code est valable 15 minutes :\n$token",
        ),
    )

    @Test
    fun truncatedCodeIsRefused() = assertNull(extractInviteToken(token.dropLast(1)))

    @Test
    fun longerHexRunIsRefused() = assertNull(extractInviteToken(token + "0"))

    @Test
    fun noCode() = assertNull(extractInviteToken("salut, tu me rejoins ?"))
}

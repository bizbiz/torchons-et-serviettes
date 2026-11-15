package com.torchons.app.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

// Same value as ClipDescription.EXTRA_IS_SENSITIVE, which only exists from API 33.
// Older clipboard UIs that know the key honour it; the others ignore it.
private const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

/**
 * Copies a secret (recovery code, invite token) to the clipboard and flags it as
 * sensitive so the system clipboard preview and overlays do not display it.
 */
fun copySensitiveToClipboard(context: Context, label: String, secret: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, secret)
    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        EXTRA_IS_SENSITIVE_KEY
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
    cm.setPrimaryClip(clip)
}

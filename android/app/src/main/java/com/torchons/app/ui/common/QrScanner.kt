package com.torchons.app.ui.common

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Launches the Google Play services QR scanner (no CAMERA permission needed: the camera is
 * handled by Play services). The scanning module is pre-downloaded at install time through the
 * `com.google.mlkit.vision.DEPENDENCIES` manifest meta-data.
 *
 * Callbacks are all optional in practice: cancellation and failure (module not ready, Play
 * services unavailable) never throw, they just report through [onFailure] / [onCanceled].
 */
fun startQrScan(
    context: Context,
    onResult: (String) -> Unit,
    onCanceled: () -> Unit = {},
    onFailure: (Exception) -> Unit = {},
) {
    try {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode -> onResult(barcode.rawValue ?: "") }
            .addOnCanceledListener { onCanceled() }
            .addOnFailureListener { e -> onFailure(e) }
    } catch (e: Exception) {
        onFailure(e)
    }
}

package com.unshoo.pixelmusic.data.tv

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import timber.log.Timber

object TvQrScannerHelper {
    private const val TAG = "TvQrScannerHelper"

    /**
     * Launches Google Code Scanner with auto-zoom enabled to effortlessly
     * scan QR codes displayed on distant TV screens across the room.
     */
    fun scanTvQr(
        context: Context,
        onSuccess: (String) -> Unit,
        onFailure: ((Exception) -> Unit)? = null,
        onCanceled: (() -> Unit)? = null
    ) {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(context, options)

            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue ?: barcode.displayValue.orEmpty()
                    Timber.d("$TAG: Scanned barcode rawValue: $rawValue")
                    if (rawValue.isNotBlank()) {
                        vibrateSuccess(context)
                        onSuccess(rawValue)
                    } else {
                        onFailure?.invoke(IllegalArgumentException("Empty QR code"))
                    }
                }
                .addOnCanceledListener {
                    Timber.d("$TAG: Scan canceled by user")
                    onCanceled?.invoke()
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "$TAG: Scan failed")
                    onFailure?.invoke(e)
                }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error launching scanner")
            onFailure?.invoke(e)
        }
    }

    private fun vibrateSuccess(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(45)
                }
            }
        } catch (_: Exception) {}
    }
}

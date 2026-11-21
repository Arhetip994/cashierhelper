package com.unshoo.pixelmusic.tv.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * High-contrast, sharp QR code Composable for Android TV.
 * Uses pure in-app Nayuki QR generator with 0 external network dependencies.
 */
@Composable
fun TvQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    backgroundColor: Color = Color.White,
    moduleColor: Color = Color.Black
) {
    val qr = remember(content) {
        try {
            if (content.isNotBlank()) {
                // Use LOW error correction: produces fewer, larger modules for effortless camera scanning off TV/monitor screens
                QrCode.encodeText(content, QrCode.Ecc.LOW)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Outer white container with generous padding (quiet zone) as required by the QR code specification
    Box(
        modifier = modifier
            .size(size)
            .background(backgroundColor, RoundedCornerShape(16.dp))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (qr != null) {
            Canvas(modifier = Modifier.size(size - 36.dp)) {
                val qrSize = qr.size
                if (qrSize > 0) {
                    val moduleSize = this.size.minDimension / qrSize.toFloat()
                    for (y in 0 until qrSize) {
                        for (x in 0 until qrSize) {
                            if (qr.getModule(x, y)) {
                                drawRect(
                                    color = moduleColor,
                                    topLeft = Offset(x * moduleSize, y * moduleSize),
                                    size = Size(moduleSize, moduleSize)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

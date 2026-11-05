/*
 * Copyright (c) 2025 Proton AG
 * This file is part of Proton AG and Proton Authenticator.
 *
 * Proton Authenticator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Authenticator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.authenticator.shared.common.scanners

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.withContext
import proton.android.authenticator.commonrust.QrCodeScannerInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.domain.scanners.QrScanner
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

internal class RustQrScanner @Inject constructor(
    private val appDispatchers: AppDispatchers,
    private val contentResolver: ContentResolver,
    private val qrCodeScanner: QrCodeScannerInterface
) : QrScanner {

    override suspend fun scan(uri: Uri): String? = withContext(appDispatchers.io) {
        try {
            contentResolver.openInputStream(uri)
                ?.use(InputStream::readBytes)
                ?.let { imageByteArray -> scan(bytes = imageByteArray) }
        } catch (error: FileNotFoundException) {
            AuthenticatorLogger.w(TAG, "There was an error while scanning the QR code: FileNotFoundException")
            AuthenticatorLogger.w(TAG, error)

            null
        } catch (error: IOException) {
            AuthenticatorLogger.w(TAG, "There was an error while scanning the QR code: IOException")
            AuthenticatorLogger.w(TAG, error)

            null
        }
    }

    override suspend fun scan(bytes: ByteArray): String? = withContext(appDispatchers.default) {
        val textCandidate = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrNull()
        if (textCandidate != null && (
            textCandidate.startsWith("otpauth-migration://", ignoreCase = true) ||
            textCandidate.startsWith("otpauth://", ignoreCase = true)
        )) {
            return@withContext textCandidate
        }

        val rustResult = qrCodeScanner.scanQrCode(image = bytes)
        if (!rustResult.isNullOrBlank()) {
            return@withContext rustResult
        }
        decodeWithZxing(bytes)
    }

    private fun decodeWithZxing(bytes: ByteArray): String? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true
                )
            )
        }
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            try {
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            }
        } finally {
            reader.reset()
        }
    }.getOrNull()

    private companion object {

        private const val TAG = "RustQrScanner"

    }

}

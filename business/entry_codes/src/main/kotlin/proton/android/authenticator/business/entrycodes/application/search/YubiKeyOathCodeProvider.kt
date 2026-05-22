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

package proton.android.authenticator.business.entrycodes.application.search

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Base64
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.oath.Code
import com.yubico.yubikit.oath.Credential
import com.yubico.yubikit.oath.OathSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import proton.android.authenticator.business.entrycodes.domain.EntryCode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume

internal class YubiKeyOathCodeProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val codeCache = ConcurrentHashMap<String, CachedCode>()

    internal suspend fun calculate(uri: String, nowSeconds: Long): EntryCode {
        val request = YubiKeyOathCodeRequest.parse(uri)
        val periodIndex = nowSeconds / request.periodSeconds

        if (!hasConnectedYubiKey()) {
            codeCache.remove(uri)
            throw YubiKeyOathUnavailableError()
        }

        codeCache[uri]
            ?.takeIf { cachedCode -> cachedCode.periodIndex == periodIndex }
            ?.let { cachedCode -> return cachedCode.entryCode }

        return withUsbOathSession { oathSession ->
            val credential = oathSession.getCredentials()
                .firstOrNull { credential -> credential.getId().contentEquals(request.credentialId) }
                ?: throw YubiKeyOathCredentialNotFoundError()

            val currentCode = oathSession.calculateCode(credential, nowSeconds)
            val nextCode = oathSession.calculateCode(credential, currentCode.validUntil)

            EntryCode(
                currentCode = currentCode.value,
                nextCode = nextCode.value
            ).also { entryCode ->
                codeCache[uri] = CachedCode(
                    periodIndex = periodIndex,
                    entryCode = entryCode
                )
            }
        }
    }

    private suspend fun <R> withUsbOathSession(block: (OathSession) -> R): R = withTimeout(USB_DISCOVERY_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val manager = YubiKitManager(context)
            val resumed = AtomicBoolean(false)

            fun resumeOnce(result: Result<R>) {
                if (!resumed.compareAndSet(false, true)) return

                manager.stopUsbDiscovery()
                continuation.resume(result)
            }

            continuation.invokeOnCancellation {
                manager.stopUsbDiscovery()
            }

            manager.startUsbDiscovery(
                UsbConfiguration().handlePermissions(true)
            ) { device ->
                resumeOnce(runCatching { device.useOathSession(block) })
            }
        }.getOrThrow()
    }

    private fun <R> UsbYubiKeyDevice.useOathSession(block: (OathSession) -> R): R = use {
        openConnection(SmartCardConnection::class.java).use { connection ->
            OathSession(connection).use { oathSession ->
                if (oathSession.isLocked) {
                    throw YubiKeyOathLockedError()
                }

                block(oathSession)
            }
        }
    }

    private fun hasConnectedYubiKey(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.any { device ->
            device.vendorId == YUBICO_VENDOR_ID
        }
    }

    private data class CachedCode(
        val periodIndex: Long,
        val entryCode: EntryCode
    )

    private data class YubiKeyOathCodeRequest(
        val credentialId: ByteArray,
        val periodSeconds: Long
    ) {

        companion object {
            fun parse(uri: String): YubiKeyOathCodeRequest {
                val parsedUri = URI(uri)
                require(parsedUri.scheme == YUBIKEY_OATH_SCHEME) {
                    "Unsupported YubiKey OATH uri scheme: ${parsedUri.scheme}"
                }

                val credentialId = parsedUri.path
                    .removePrefix("/")
                    .let { encodedCredentialId ->
                        Base64.decode(encodedCredentialId, Base64.URL_SAFE or Base64.NO_WRAP)
                    }

                val periodSeconds = parsedUri.query
                    ?.split("&")
                    ?.firstNotNullOfOrNull { queryPart ->
                        queryPart.substringAfter("period=", missingDelimiterValue = "")
                            .takeIf(String::isNotEmpty)
                            ?.toLongOrNull()
                    }
                    ?.takeIf { period -> period > 0 }
                    ?: DEFAULT_PERIOD_SECONDS

                return YubiKeyOathCodeRequest(
                    credentialId = credentialId,
                    periodSeconds = periodSeconds
                )
            }
        }
    }

    private companion object {
        private const val USB_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_PERIOD_SECONDS = 30L
        private const val YUBICO_VENDOR_ID = 0x1050
        private const val YUBIKEY_OATH_SCHEME = "yubikey-oath"
    }
}

internal class YubiKeyOathCredentialNotFoundError : Exception()

internal class YubiKeyOathLockedError : Exception()

internal class YubiKeyOathUnavailableError : Exception()

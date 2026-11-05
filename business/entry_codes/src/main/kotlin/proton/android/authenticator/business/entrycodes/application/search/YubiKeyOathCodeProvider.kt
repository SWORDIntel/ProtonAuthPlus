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
        return calculateBatch(listOf(uri), nowSeconds)[uri]
            ?: throw YubiKeyOathCredentialNotFoundError()
    }

    internal suspend fun calculateBatch(uris: List<String>, nowSeconds: Long): Map<String, EntryCode> {
        if (uris.isEmpty()) return emptyMap()

        if (!hasConnectedYubiKey()) {
            codeCache.clear()
            throw YubiKeyOathUnavailableError()
        }

        val requestsByUri = uris.distinct().associateWith { uri ->
            runCatching { YubiKeyOathCodeRequest.parse(uri) }.getOrNull()
        }

        val resultMap = mutableMapOf<String, EntryCode>()
        val urisToCalculate = mutableListOf<Pair<String, YubiKeyOathCodeRequest>>()

        for ((uri, request) in requestsByUri) {
            if (request == null) continue
            val periodIndex = nowSeconds / request.periodSeconds
            val cached = codeCache[uri]
            if (cached != null && cached.periodIndex == periodIndex) {
                resultMap[uri] = cached.entryCode
            } else {
                urisToCalculate.add(uri to request)
            }
        }

        if (urisToCalculate.isEmpty()) {
            return resultMap
        }

        val calculated = try {
            withUsbOathSession { oathSession ->
                calculateOathBatch(oathSession, urisToCalculate, nowSeconds)
            }
        } catch (e: Exception) {
            if (!hasConnectedYubiKey()) {
                codeCache.clear()
            }
            throw e
        }

        for ((uri, entryCode) in calculated) {
            resultMap[uri] = entryCode
            val request = requestsByUri[uri] ?: continue
            val periodIndex = nowSeconds / request.periodSeconds
            codeCache[uri] = CachedCode(
                periodIndex = periodIndex,
                entryCode = entryCode
            )
        }

        return resultMap
    }

    private fun calculateOathBatch(
        oathSession: OathSession,
        urisToCalculate: List<Pair<String, YubiKeyOathCodeRequest>>,
        nowSeconds: Long
    ): Map<String, EntryCode> {
        val nowMillis = nowSeconds * MILLIS_PER_SECOND
        val calculatedCodes: Map<Credential, Code?> = runCatching {
            oathSession.calculateCodes(nowMillis)
        }.getOrDefault(emptyMap())

        val fallbackCredentials by lazy {
            runCatching { oathSession.getCredentials() }.getOrDefault(emptyList())
        }

        val result = mutableMapOf<String, EntryCode>()

        for ((uri, request) in urisToCalculate) {
            val calculateEntry = calculatedCodes.entries.firstOrNull { (cred, _) ->
                cred.getId().contentEquals(request.credentialId)
            }

            val credential = calculateEntry?.key
                ?: fallbackCredentials.firstOrNull { it.getId().contentEquals(request.credentialId) }
                ?: continue

            val isTouchRequired = credential.isTouchRequired
            val preCalculatedCode = calculateEntry?.value

            val currentCodeResult = if (preCalculatedCode != null) {
                preCalculatedCode
            } else {
                runCatching {
                    oathSession.calculateCode(credential, nowMillis)
                }.getOrNull() ?: continue
            }

            val nextCode = if (isTouchRequired) {
                CODE_PLACEHOLDER
            } else {
                runCatching {
                    oathSession.calculateCode(credential, currentCodeResult.validUntil).value
                }.getOrDefault(CODE_PLACEHOLDER)
            }

            result[uri] = EntryCode(
                currentCode = currentCodeResult.value,
                nextCode = nextCode
            )
        }

        return result
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
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
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
        private const val USB_DISCOVERY_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_PERIOD_SECONDS = 30L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val CODE_PLACEHOLDER = "------"
        private const val YUBICO_VENDOR_ID = 0x1050
        private const val YUBIKEY_OATH_SCHEME = "yubikey-oath"
    }
}

internal class YubiKeyOathCredentialNotFoundError : Exception()

internal class YubiKeyOathLockedError : Exception()

internal class YubiKeyOathUnavailableError : Exception()

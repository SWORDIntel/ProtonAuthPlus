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

package proton.android.authenticator.business.entries.application.delete

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Base64
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.oath.OathSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialNotFoundError
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume

internal interface YubiKeyOathCredentialDeleter {
    fun hasConnectedYubiKey(): Boolean
    suspend fun delete(credentialId: String)
    suspend fun delete(backend: EntryCredentialBackend.YubiKeyOath) = delete(backend.credentialId)
    suspend fun deleteIfConnected(credentialId: String): Boolean
    suspend fun deleteIfConnected(backend: EntryCredentialBackend.YubiKeyOath): Boolean =
        deleteIfConnected(backend.credentialId)
}

internal class YubiKeyOathCredentialDeleterImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : YubiKeyOathCredentialDeleter {

    override fun hasConnectedYubiKey(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.deviceList.values.any { device ->
            device.vendorId == YUBICO_VENDOR_ID
        }
    }

    override suspend fun delete(credentialId: String) {
        if (!hasConnectedYubiKey()) {
            throw YubiKeyOathUnavailableError()
        }

        val rawCredentialId = try {
            Base64.decode(credentialId, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Base64.decode(credentialId, Base64.DEFAULT)
        }

        withUsbOathSession { oathSession ->
            if (oathSession.isLocked) {
                throw YubiKeyOathDeleteLockedError()
            }

            val credential = oathSession.credentials
                .firstOrNull { it.id.contentEquals(rawCredentialId) }

            if (credential == null) {
                throw YubiKeyOathCredentialNotFoundError()
            }
            oathSession.deleteCredential(credential)
            AuthenticatorLogger.d(TAG, "Successfully deleted credential from YubiKey")
        }
    }

    override suspend fun deleteIfConnected(credentialId: String): Boolean {
        if (!hasConnectedYubiKey()) {
            AuthenticatorLogger.d(TAG, "YubiKey not connected; skipping physical credential deletion")
            return false
        }
        return try {
            delete(credentialId)
            true
        } catch (e: Exception) {
            AuthenticatorLogger.w(TAG, "Failed to delete credential from YubiKey: ${e.message}")
            AuthenticatorLogger.w(TAG, e)
            false
        }
    }

    private suspend fun <R> withUsbOathSession(block: (OathSession) -> R): R =
        withTimeout(USB_DISCOVERY_TIMEOUT_MILLIS) {
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
            OathSession(connection).use(block)
        }
    }

    private companion object {
        private const val TAG = "YubiKeyOathCredentialDeleter"
        private const val USB_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        private const val YUBICO_VENDOR_ID = 0x1050
    }
}

internal class YubiKeyOathUnavailableError : Exception("YubiKey is not connected")

internal class YubiKeyOathDeleteLockedError : Exception("YubiKey OATH session is locked")

internal class YubiKeyOathCredentialDeleteError(cause: Throwable) :
    Exception("Failed to delete credential from YubiKey", cause)

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

package proton.android.authenticator.business.entries.application.create

import android.content.Context
import android.util.Base64
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.oath.Base32
import com.yubico.yubikit.oath.CredentialData
import com.yubico.yubikit.oath.HashAlgorithm
import com.yubico.yubikit.oath.OathSession
import com.yubico.yubikit.oath.OathType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume

internal class YubiKeyOathCredentialWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    internal suspend fun write(command: CreateEntryCommand.FromYubiKeyTotp): YubiKeyOathCredentialWriteResult =
        withUsbOathSession { oathSession ->
            if (oathSession.isLocked) {
                throw YubiKeyOathEnrollmentLockedError()
            }

            val credentialData = CredentialData(
                command.name,
                OathType.TOTP,
                command.algorithm.toYubiKeyHashAlgorithm(),
                Base32.decode(command.secret),
                command.digits,
                command.period,
                0,
                command.issuer
            )

            oathSession.putCredential(credentialData, false).let { credential ->
                YubiKeyOathCredentialWriteResult(
                    credentialId = Base64.encodeToString(credential.id, Base64.URL_SAFE or Base64.NO_WRAP),
                    deviceId = oathSession.deviceId
                )
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
            OathSession(connection).use(block)
        }
    }

    private fun EntryAlgorithm.toYubiKeyHashAlgorithm(): HashAlgorithm = when (this) {
        EntryAlgorithm.SHA1 -> HashAlgorithm.SHA1
        EntryAlgorithm.SHA256 -> HashAlgorithm.SHA256
        EntryAlgorithm.SHA512 -> HashAlgorithm.SHA512
    }

    private companion object {
        private const val USB_DISCOVERY_TIMEOUT_MILLIS = 10_000L
    }
}

internal data class YubiKeyOathCredentialWriteResult(
    val credentialId: String,
    val deviceId: String?
)

internal class YubiKeyOathEnrollmentLockedError : Exception()

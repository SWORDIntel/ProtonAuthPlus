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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HashMap

class YubiKeyOathCodeProviderTest {

    private val usbManager: UsbManager = mockk()
    private val context: Context = mockk {
        every { getSystemService(Context.USB_SERVICE) } returns usbManager
    }

    private val provider = YubiKeyOathCodeProvider(context)

    @Test
    fun `calculateBatch with empty uris returns empty map immediately`() = runTest {
        val result = provider.calculateBatch(emptyList(), nowSeconds = 1_700_000_000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `calculateBatch throws YubiKeyOathUnavailableError when no YubiKey is connected`() {
        every { usbManager.deviceList } returns HashMap()

        assertThrows(YubiKeyOathUnavailableError::class.java) {
            runBlocking {
                provider.calculateBatch(
                    listOf("yubikey-oath://dGVzdA=="),
                    nowSeconds = 1_700_000_000L
                )
            }
        }
    }

    @Test
    fun `calculateBatch throws YubiKeyOathUnavailableError when non-yubico device is connected`() {
        val otherDevice: UsbDevice = mockk {
            every { vendorId } returns 0x1234
        }
        val devices = HashMap<String, UsbDevice>().apply {
            put("dev1", otherDevice)
        }
        every { usbManager.deviceList } returns devices

        assertThrows(YubiKeyOathUnavailableError::class.java) {
            runBlocking {
                provider.calculateBatch(
                    listOf("yubikey-oath://dGVzdA=="),
                    nowSeconds = 1_700_000_000L
                )
            }
        }
    }
}

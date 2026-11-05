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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import proton.android.authenticator.business.entrycodes.domain.EntryCode
import proton.android.authenticator.business.entrycodes.domain.EntryCodeAvailability
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.commonrust.MobileTotpGeneratorInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.domain.providers.TimeProvider

@OptIn(ExperimentalCoroutinesApi::class)
class EntryCodesSearcherTest {

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val totpGenerator: MobileTotpGeneratorInterface = mockk()
    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = object : AppDispatchers {
        override val main = testDispatcher
        override val default = testDispatcher
        override val io = testDispatcher
    }
    private val timeProvider: TimeProvider = mockk {
        every { currentSeconds() } returns 1_700_000_000L
    }
    private val yubiKeyOathCodeProvider: YubiKeyOathCodeProvider = mockk()

    private val searcher = EntryCodesSearcher(
        authenticatorClient = authenticatorClient,
        totpGenerator = totpGenerator,
        appDispatchers = appDispatchers,
        timeProvider = timeProvider,
        yubiKeyOathCodeProvider = yubiKeyOathCodeProvider
    )

    @Test
    fun `search with empty uris returns empty list`() = runTest(testDispatcher) {
        val result = searcher.search(emptyList()).first()
        assertEquals(emptyList<EntryCode>(), result)
    }

    @Test
    fun `search with multiple yubikey uris uses calculateBatch and emits codes`() = runTest(testDispatcher) {
        val uri1 = "yubikey-oath://credential1"
        val uri2 = "yubikey-oath://credential2"
        val code1 = EntryCode(currentCode = "123456", nextCode = "654321")
        val code2 = EntryCode(currentCode = "789012", nextCode = "------")

        coEvery {
            yubiKeyOathCodeProvider.calculateBatch(
                uris = listOf(uri1, uri2),
                nowSeconds = 1_700_000_000L
            )
        } returns mapOf(
            uri1 to code1,
            uri2 to code2
        )

        val result = searcher.search(listOf(uri1, uri2)).first()

        assertEquals(listOf(code1, code2), result)
        coVerify(exactly = 1) {
            yubiKeyOathCodeProvider.calculateBatch(
                uris = listOf(uri1, uri2),
                nowSeconds = 1_700_000_000L
            )
        }
    }

    @Test
    fun `search with yubikey calculateBatch failure falls back to unavailable code`() = runTest(testDispatcher) {
        val uri1 = "yubikey-oath://credential1"

        coEvery {
            yubiKeyOathCodeProvider.calculateBatch(
                uris = listOf(uri1),
                nowSeconds = 1_700_000_000L
            )
        } throws YubiKeyOathUnavailableError()

        val result = searcher.search(listOf(uri1)).first()

        assertEquals(1, result.size)
        assertEquals("------", result[0].currentCode)
        assertEquals("------", result[0].nextCode)
        assertEquals(EntryCodeAvailability.Unavailable, result[0].availability)
    }

    @Test
    fun `search with partial batch results handles missing credential as unavailable`() = runTest(testDispatcher) {
        val uri1 = "yubikey-oath://credential1"
        val uri2 = "yubikey-oath://credential2"
        val code1 = EntryCode(currentCode = "123456", nextCode = "654321")

        coEvery {
            yubiKeyOathCodeProvider.calculateBatch(
                uris = listOf(uri1, uri2),
                nowSeconds = 1_700_000_000L
            )
        } returns mapOf(uri1 to code1)

        val result = searcher.search(listOf(uri1, uri2)).first()

        assertEquals(2, result.size)
        assertEquals(code1, result[0])
        assertEquals("------", result[1].currentCode)
        assertEquals(EntryCodeAvailability.Unavailable, result[1].availability)
    }
}

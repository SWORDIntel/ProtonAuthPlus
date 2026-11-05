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

import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.TimeoutException
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.answers.Answer
import java.io.IOException

class CreateEntryCommandHandlerTest {

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val creator: EntryCreator = mockk()
    private lateinit var handler: CreateEntryCommandHandler

    private val command = CreateEntryCommand.FromYubiKeyTotp(
        name = "GitHub",
        secret = "JBSWY3DPEHPK3PXP",
        issuer = "GitHub",
        period = 30,
        digits = 6,
        algorithm = EntryAlgorithm.SHA1,
        note = null
    )

    @Before
    fun setUp() {
        handler = CreateEntryCommandHandler(
            authenticatorClient = authenticatorClient,
            creator = creator
        )
    }

    @Test
    fun `when create succeeds, returns Success`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } returns mockk()

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Success)
    }

    @Test
    fun `when TimeoutCancellationException thrown, returns YubiKeyTimeout`() = runTest {
        val timeoutException = mockk<TimeoutCancellationException>()
        coEvery { creator.createYubiKeyTotp(command) } throws timeoutException

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyTimeout, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when TimeoutException thrown, returns YubiKeyTimeout`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws TimeoutException("YubiKey timed out")

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyTimeout, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when YubiKeyOathEnrollmentLockedError thrown, returns YubiKeyLocked`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws YubiKeyOathEnrollmentLockedError()

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyLocked, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with NO_SPACE thrown, returns YubiKeyStorageFull`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws ApduException(byteArrayOf(), SW.NO_SPACE)

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyStorageFull, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with MEMORY_ERROR thrown, returns YubiKeyStorageFull`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws ApduException(byteArrayOf(), SW.MEMORY_ERROR)

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyStorageFull, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with AUTH_METHOD_BLOCKED thrown, returns YubiKeyLocked`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws ApduException(byteArrayOf(), SW.AUTH_METHOD_BLOCKED)

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyLocked, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with general error thrown, returns YubiKeyError`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws ApduException(byteArrayOf(), 0x6A80.toShort())

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when CommandException thrown, returns YubiKeyError`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws CommandException("General smartcard failure")

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when IOException thrown, returns YubiKeyError`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws IOException("USB disconnect")

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when YubiKeyOathCredentialNotFoundError thrown, returns YubiKeyError`() = runTest {
        coEvery { creator.createYubiKeyTotp(command) } throws YubiKeyOathCredentialNotFoundError()

        val answer = handler.handle(command)

        assertTrue(answer is Answer.Failure)
        assertEquals(CreateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }
}

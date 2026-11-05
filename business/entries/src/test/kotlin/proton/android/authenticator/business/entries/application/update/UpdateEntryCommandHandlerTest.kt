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

package proton.android.authenticator.business.entries.application.update

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
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialNotFoundError
import proton.android.authenticator.business.entries.application.create.YubiKeyOathEnrollmentLockedError
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.answers.Answer
import java.io.IOException

class UpdateEntryCommandHandlerTest {

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val updater: EntryUpdater = mockk()
    private lateinit var handler: UpdateEntryCommandHandler

    private val migrateCommand = UpdateEntryCommand.ToYubiKeyTotp(
        id = "entry-1",
        name = "GitHub",
        secret = "JBSWY3DPEHPK3PXP",
        issuer = "GitHub",
        period = 30,
        digits = 6,
        algorithm = EntryAlgorithm.SHA1,
        note = null,
        position = 0
    )

    @Before
    fun setUp() {
        handler = UpdateEntryCommandHandler(
            authenticatorClient = authenticatorClient,
            updater = updater
        )
    }

    @Test
    fun `when migration succeeds, returns Success`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } returns Unit

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Success)
    }

    @Test
    fun `when TimeoutCancellationException thrown during migration, returns YubiKeyTimeout`() = runTest {
        val timeoutException = mockk<TimeoutCancellationException>()
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws timeoutException

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyTimeout, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when TimeoutException thrown during migration, returns YubiKeyTimeout`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws TimeoutException("YubiKey timed out")

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyTimeout, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when YubiKeyOathEnrollmentLockedError thrown during migration, returns YubiKeyLocked`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws YubiKeyOathEnrollmentLockedError()

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyLocked, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when YubiKeyVerificationFailedException thrown during migration, returns VerificationFailed`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws YubiKeyVerificationFailedException("Mismatch")

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.VerificationFailed, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with NO_SPACE thrown, returns YubiKeyStorageFull`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws ApduException(byteArrayOf(), SW.NO_SPACE)

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyStorageFull, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with AUTH_METHOD_BLOCKED thrown, returns YubiKeyLocked`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws ApduException(byteArrayOf(), SW.AUTH_METHOD_BLOCKED)

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyLocked, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when ApduException with general error thrown, returns YubiKeyError`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws ApduException(byteArrayOf(), 0x6A80.toShort())

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when CommandException thrown during migration, returns YubiKeyError`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws CommandException("Smartcard error")

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when IOException thrown during migration, returns YubiKeyError`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws IOException("I/O error")

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when YubiKeyOathCredentialNotFoundError thrown during migration, returns YubiKeyError`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws YubiKeyOathCredentialNotFoundError()

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.YubiKeyError, (answer as Answer.Failure).reason)
    }

    @Test
    fun `when IllegalStateException thrown, returns EntryNotFound`() = runTest {
        coEvery { updater.migrateToYubiKeyTotp(migrateCommand) } throws IllegalStateException("Entry not found")

        val answer = handler.handle(migrateCommand)

        assertTrue(answer is Answer.Failure)
        assertEquals(UpdateEntryReason.EntryNotFound, (answer as Answer.Failure).reason)
    }
}

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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.providers.TimeProvider

class EntryDeleterTest {

    private val repository: EntriesRepository = mockk(relaxed = true)
    private val timeProvider: TimeProvider = mockk()
    private val yubiKeyOathCredentialDeleter: YubiKeyOathCredentialDeleter = mockk(relaxed = true)

    private lateinit var deleter: EntryDeleter
    private lateinit var commandHandler: DeleteEntryCommandHandler

    @Before
    fun setUp() {
        every { timeProvider.currentSeconds() } returns 123456L
        deleter = EntryDeleter(
            repository = repository,
            timeProvider = timeProvider,
            yubiKeyOathCredentialDeleter = yubiKeyOathCredentialDeleter
        )
        commandHandler = DeleteEntryCommandHandler(deleter = deleter)
    }

    @Test
    fun `when deleting local encrypted entry that is not synced, removes it from repository without contacting YubiKey`() =
        runTest {
        val entry = Entry(
            id = "test-id-1",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("test-id-1") } returns flowOf(entry)

        val result = deleter.delete("test-id-1")

        assertEquals("test-id-1", result.id)
        coVerify(exactly = 1) { repository.remove(entry) }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() }
        coVerify(exactly = 0) { yubiKeyOathCredentialDeleter.delete(any<String>()) }
    }

    @Test
    fun `when deleting local encrypted entry that is synced, marks it deleted without contacting YubiKey`() = runTest {
        val entry = Entry(
            id = "test-id-2",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = true,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("test-id-2") } returns flowOf(entry)

        val result = deleter.delete("test-id-2")

        assertTrue(result.isDeleted)
        assertEquals(123456L, result.modifiedAt)
        coVerify(exactly = 1) { repository.save(match { it.id == "test-id-2" && it.isDeleted && it.modifiedAt == 123456L }) }
        coVerify(exactly = 0) { repository.remove(any()) }
        coVerify(exactly = 0) { yubiKeyOathCredentialDeleter.delete(any<String>()) }
    }

    @Test
    fun `when deleting YubiKey entry and key is connected, deletes physical credential and tombstones local entry`() =
        runTest {
        val yubiKeyBackend = EntryCredentialBackend.YubiKeyOath(
            credentialId = "dGVzdC1jcmVkZW50aWFsLWlk",
            deviceId = "dev-123",
            name = "Test Account",
            issuer = "Proton",
            note = null,
            period = 30,
            algorithm = EntryAlgorithm.SHA1,
            digits = 6
        )
        val entry = Entry(
            id = "test-yubikey-1",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = yubiKeyBackend
        )
        every { repository.find("test-yubikey-1") } returns flowOf(entry)
        every { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() } returns true

        val result = deleter.delete("test-yubikey-1")

        assertEquals("test-yubikey-1", result.id)
        coVerify(exactly = 1) { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() }
        coVerify(exactly = 1) { yubiKeyOathCredentialDeleter.delete("dGVzdC1jcmVkZW50aWFsLWlk") }
        coVerify(exactly = 1) { repository.save(match { it.id == entry.id && it.isDeleted && it.credentialBackend == yubiKeyBackend }) }
        coVerify(exactly = 0) { repository.remove(any()) }
    }

    @Test
    fun `when deleting YubiKey entry and key is disconnected, keeps entry and returns unavailable error`() = runTest {
        val yubiKeyBackend = EntryCredentialBackend.YubiKeyOath(
            credentialId = "dGVzdC1jcmVkZW50aWFsLWlk",
            deviceId = "dev-123",
            name = "Test Account",
            issuer = "Proton",
            note = null,
            period = 30,
            algorithm = EntryAlgorithm.SHA1,
            digits = 6
        )
        val entry = Entry(
            id = "test-yubikey-2",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = yubiKeyBackend
        )
        every { repository.find("test-yubikey-2") } returns flowOf(entry)
        every { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() } returns false

        val answer = commandHandler.handle(DeleteEntryCommand(id = "test-yubikey-2"))
        assertTrue(answer is Answer.Failure)
        assertEquals(DeleteEntryReason.YubiKeyUnavailable, (answer as Answer.Failure).reason)

        coVerify(exactly = 1) { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() }
        coVerify(exactly = 0) { yubiKeyOathCredentialDeleter.delete(any<String>()) }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { repository.remove(any()) }
    }

    @Test
    fun `when deleting YubiKey entry and physical deletion fails, keeps entry and returns failure`() =
        runTest {
        val yubiKeyBackend = EntryCredentialBackend.YubiKeyOath(
            credentialId = "dGVzdC1jcmVkZW50aWFsLWlk",
            deviceId = "dev-123",
            name = "Test Account",
            issuer = "Proton",
            note = null,
            period = 30,
            algorithm = EntryAlgorithm.SHA1,
            digits = 6
        )
        val entry = Entry(
            id = "test-yubikey-3",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = yubiKeyBackend
        )
        every { repository.find("test-yubikey-3") } returns flowOf(entry)
        every { yubiKeyOathCredentialDeleter.hasConnectedYubiKey() } returns true
        coEvery { yubiKeyOathCredentialDeleter.delete("dGVzdC1jcmVkZW50aWFsLWlk") } throws YubiKeyOathDeleteLockedError()

        val error = runCatching { deleter.delete("test-yubikey-3") }.exceptionOrNull()
        assertTrue(error is YubiKeyOathCredentialDeleteError)

        coVerify(exactly = 1) { yubiKeyOathCredentialDeleter.delete("dGVzdC1jcmVkZW50aWFsLWlk") }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { repository.remove(any()) }
    }

    @Test
    fun `command handler returns Success on successful delete`() = runTest {
        val entry = Entry(
            id = "test-cmd-1",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 1000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("test-cmd-1") } returns flowOf(entry)

        val answer = commandHandler.handle(DeleteEntryCommand(id = "test-cmd-1"))

        assertTrue(answer is Answer.Success)
        assertEquals("test-cmd-1", (answer as Answer.Success).data.id)
    }

    @Test
    fun `command handler returns EntryNotFound on missing entry`() = runTest {
        every { repository.find("missing-id") } throws NoSuchElementException("Not found")

        val answer = commandHandler.handle(DeleteEntryCommand(id = "missing-id"))

        assertTrue(answer is Answer.Failure)
        assertEquals(DeleteEntryReason.EntryNotFound, (answer as Answer.Failure).reason)
    }
}

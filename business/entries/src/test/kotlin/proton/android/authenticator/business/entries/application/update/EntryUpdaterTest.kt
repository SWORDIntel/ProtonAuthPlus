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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.AuthenticatorCodeResponseModel
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.AuthenticatorEntryTotpParameters
import proton.android.authenticator.AuthenticatorEntryTotpCreateParameters
import proton.android.authenticator.AuthenticatorEntryType
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialWriteResult
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialWriter
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag

class EntryUpdaterTest {

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val encryptionContext: EncryptionContext = mockk()
    private val encryptionContextProvider: EncryptionContextProvider = object : EncryptionContextProvider {
        override suspend fun <R> withEncryptionContext(
            encryptionKey: proton.android.authenticator.shared.crypto.domain.keys.EncryptionKey?,
            block: suspend EncryptionContext.() -> R
        ): R = encryptionContext.block()
    }
    private val yubiKeyOathCredentialWriter: YubiKeyOathCredentialWriter = mockk()
    private val repository: EntriesRepository = mockk(relaxed = true)
    private val timeProvider: TimeProvider = mockk()

    private lateinit var updater: EntryUpdater

    private val sampleCommand = UpdateEntryCommand.ToYubiKeyTotp(
        id = "entry-1",
        name = "GitHub",
        secret = "JBSWY3DPEHPK3PXP",
        issuer = "GitHub",
        period = 30,
        digits = 6,
        algorithm = EntryAlgorithm.SHA1,
        note = "my note",
        position = 0
    )

    private val sampleModel = AuthenticatorEntryModel(
        id = "model-id-1",
        name = "GitHub",
        issuer = "GitHub",
        note = "my note",
        secret = "JBSWY3DPEHPK3PXP",
        uri = "otpauth://totp/GitHub:GitHub?secret=JBSWY3DPEHPK3PXP",
        period = 30u,
        entryType = AuthenticatorEntryType.TOTP
    )

    @Before
    fun setUp() {
        updater = EntryUpdater(
            authenticatorClient = authenticatorClient,
            encryptionContextProvider = encryptionContextProvider,
            yubiKeyOathCredentialWriter = yubiKeyOathCredentialWriter,
            repository = repository,
            timeProvider = timeProvider
        )

        every { timeProvider.currentSeconds() } returns 1000L
    }

    @Test
    fun `update on existing YubiKeyOath entries preserves hardware backend and does not overwrite with local encryption`() =
        runTest {
            val yubiBackend = EntryCredentialBackend.YubiKeyOath(
                credentialId = "yubi-cred-orig",
                deviceId = "dev-orig",
                name = "Old Name",
                issuer = "Old Issuer",
                note = "Old Note",
                period = 30,
                algorithm = EntryAlgorithm.SHA1,
                digits = 6
            )

            val existingEntry = Entry(
                id = "entry-hardware",
                content = EncryptedByteArray(byteArrayOf()),
                createdAt = 100L,
                modifiedAt = 200L,
                isDeleted = false,
                isSynced = true,
                position = 2,
                credentialBackend = yubiBackend
            )
            every { repository.find("entry-hardware") } returns flowOf(existingEntry)

            val updatedModel = AuthenticatorEntryModel(
                id = "entry-hardware",
                name = "New Name",
                issuer = "New Issuer",
                note = "New Note",
                secret = "",
                uri = "",
                period = 30u,
                entryType = AuthenticatorEntryType.TOTP
            )

            updater.update(id = "entry-hardware", position = 10, model = updatedModel)

            val savedSlot = slot<Entry>()
            coVerify(exactly = 1) { repository.save(capture(savedSlot)) }

            val saved = savedSlot.captured
            assertEquals("entry-hardware", saved.id)
            assertEquals(10, saved.position)
            assertEquals(1000L, saved.modifiedAt)
            assertTrue(saved.credentialBackend is EntryCredentialBackend.YubiKeyOath)

            val savedBackend = saved.credentialBackend as EntryCredentialBackend.YubiKeyOath
            assertEquals("yubi-cred-orig", savedBackend.credentialId)
            assertEquals("dev-orig", savedBackend.deviceId)
            assertEquals(30, savedBackend.period)
            assertEquals(6, savedBackend.digits)
            assertEquals(EntryAlgorithm.SHA1, savedBackend.algorithm)
            assertEquals("New Name", savedBackend.name)
            assertEquals("New Issuer", savedBackend.issuer)
            assertEquals("New Note", savedBackend.note)

            coVerify(exactly = 0) { authenticatorClient.serializeEntry(any()) }
            coVerify(exactly = 0) { encryptionContext.encrypt(any(), any()) }
        }

    @Test
    fun `when TOTP codes match during migration, local secret is replaced and entry is saved`() = runTest {
        val originalSecretBytes = EncryptedByteArray(byteArrayOf(9, 8, 7))
        val emptyEncryptedContent = EncryptedByteArray(byteArrayOf(0, 0, 0))

        val existingEntry = Entry(
            id = "entry-1",
            content = originalSecretBytes,
            createdAt = 500L,
            modifiedAt = 500L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("entry-1") } returns flowOf(existingEntry)

        coEvery { yubiKeyOathCredentialWriter.write(any()) } returns YubiKeyOathCredentialWriteResult(
            credentialId = "yubikey-cred-123",
            deviceId = "dev-456"
        )

        every { encryptionContext.decrypt(originalSecretBytes, EncryptionTag.EntryContent) } returns byteArrayOf(1, 2)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1, 2)) } returns sampleModel
        every { authenticatorClient.generateCodes(listOf(sampleModel), 1000uL) } returns listOf(
            AuthenticatorCodeResponseModel(
                currentCode = "123456",
                nextCode = "654321",
                entry = sampleModel
            )
        )

        coEvery { yubiKeyOathCredentialWriter.calculateCode("yubikey-cred-123", 1000L) } returns "123456"

        every { encryptionContext.encrypt(match { it.isEmpty() }, EncryptionTag.EntryContent) } returns emptyEncryptedContent

        updater.migrateToYubiKeyTotp(sampleCommand)

        val savedSlot = slot<Entry>()
        coVerify(exactly = 1) { repository.save(capture(savedSlot)) }

        val saved = savedSlot.captured
        assertEquals("entry-1", saved.id)
        assertEquals(emptyEncryptedContent, saved.content)
        assertEquals(1000L, saved.modifiedAt)
        assertTrue(saved.credentialBackend is EntryCredentialBackend.YubiKeyOath)
        val ykBackend = saved.credentialBackend as EntryCredentialBackend.YubiKeyOath
        assertEquals("yubikey-cred-123", ykBackend.credentialId)
        assertEquals("dev-456", ykBackend.deviceId)
        assertEquals(false, saved.isSynced)
    }

    @Test
    fun `when migrating synced entry with deleteCloudCopy=true, migrated entry retains isSynced=true`() = runTest {
        val originalSecretBytes = EncryptedByteArray(byteArrayOf(1, 2, 3))
        val emptyEncryptedContent = EncryptedByteArray(byteArrayOf(0, 0, 0))
        val existingEntry = Entry(
            id = "entry-1",
            content = originalSecretBytes,
            createdAt = 500L,
            modifiedAt = 500L,
            isDeleted = false,
            isSynced = true,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("entry-1") } returns flowOf(existingEntry)
        coEvery { yubiKeyOathCredentialWriter.write(any()) } returns YubiKeyOathCredentialWriteResult(
            credentialId = "yubikey-cred-123",
            deviceId = "dev-456"
        )
        every { encryptionContext.decrypt(originalSecretBytes, EncryptionTag.EntryContent) } returns byteArrayOf(1, 2)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1, 2)) } returns sampleModel
        every { authenticatorClient.generateCodes(listOf(sampleModel), 1000uL) } returns listOf(
            AuthenticatorCodeResponseModel(currentCode = "123456", nextCode = "654321", entry = sampleModel)
        )
        coEvery { yubiKeyOathCredentialWriter.calculateCode("yubikey-cred-123", 1000L) } returns "123456"
        every { encryptionContext.encrypt(match { it.isEmpty() }, EncryptionTag.EntryContent) } returns emptyEncryptedContent

        updater.migrateToYubiKeyTotp(sampleCommand.copy(deleteCloudCopy = true))

        val savedSlot = slot<Entry>()
        coVerify(exactly = 1) { repository.save(capture(savedSlot)) }
        assertEquals(true, savedSlot.captured.isSynced)
    }

    @Test
    fun `when migrating synced entry with deleteCloudCopy=false, migrated entry sets isSynced=false`() = runTest {
        val originalSecretBytes = EncryptedByteArray(byteArrayOf(1, 2, 3))
        val emptyEncryptedContent = EncryptedByteArray(byteArrayOf(0, 0, 0))
        val existingEntry = Entry(
            id = "entry-1",
            content = originalSecretBytes,
            createdAt = 500L,
            modifiedAt = 500L,
            isDeleted = false,
            isSynced = true,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("entry-1") } returns flowOf(existingEntry)
        coEvery { yubiKeyOathCredentialWriter.write(any()) } returns YubiKeyOathCredentialWriteResult(
            credentialId = "yubikey-cred-123",
            deviceId = "dev-456"
        )
        every { encryptionContext.decrypt(originalSecretBytes, EncryptionTag.EntryContent) } returns byteArrayOf(1, 2)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1, 2)) } returns sampleModel
        every { authenticatorClient.generateCodes(listOf(sampleModel), 1000uL) } returns listOf(
            AuthenticatorCodeResponseModel(currentCode = "123456", nextCode = "654321", entry = sampleModel)
        )
        coEvery { yubiKeyOathCredentialWriter.calculateCode("yubikey-cred-123", 1000L) } returns "123456"
        every { encryptionContext.encrypt(match { it.isEmpty() }, EncryptionTag.EntryContent) } returns emptyEncryptedContent

        updater.migrateToYubiKeyTotp(sampleCommand.copy(deleteCloudCopy = false))

        val savedSlot = slot<Entry>()
        coVerify(exactly = 1) { repository.save(capture(savedSlot)) }
        assertEquals(false, savedSlot.captured.isSynced)
    }

    @Test
    fun `when TOTP codes do not match during migration, throws verification exception and does NOT save entry`() =
        runTest {
            val originalSecretBytes = EncryptedByteArray(byteArrayOf(9, 8, 7))

            val existingEntry = Entry(
                id = "entry-1",
                content = originalSecretBytes,
                createdAt = 500L,
                modifiedAt = 500L,
                isDeleted = false,
                isSynced = false,
                position = 0,
                credentialBackend = EntryCredentialBackend.LocalEncrypted
            )
            every { repository.find("entry-1") } returns flowOf(existingEntry)

            coEvery { yubiKeyOathCredentialWriter.write(any()) } returns YubiKeyOathCredentialWriteResult(
                credentialId = "yubikey-cred-123",
                deviceId = "dev-456"
            )

            every { encryptionContext.decrypt(originalSecretBytes, EncryptionTag.EntryContent) } returns byteArrayOf(1, 2)
            every { authenticatorClient.deserializeEntry(byteArrayOf(1, 2)) } returns sampleModel
            every { authenticatorClient.generateCodes(listOf(sampleModel), 1000uL) } returns listOf(
                AuthenticatorCodeResponseModel(
                    currentCode = "123456",
                    nextCode = "654321",
                    entry = sampleModel
                )
            )

            coEvery { yubiKeyOathCredentialWriter.calculateCode("yubikey-cred-123", 1000L) } returns "999999"

            var thrown = false
            try {
                updater.migrateToYubiKeyTotp(sampleCommand)
            } catch (e: YubiKeyVerificationFailedException) {
                thrown = true
            }
            assertTrue("Expected YubiKeyVerificationFailedException", thrown)

            coVerify(exactly = 0) { repository.save(any()) }
            coVerify(exactly = 0) { encryptionContext.encrypt(match { it.isEmpty() }, any()) }
        }

    @Test
    fun `when YubiKey code calculation fails, throws exception and does NOT save entry`() = runTest {
        val originalSecretBytes = EncryptedByteArray(byteArrayOf(9, 8, 7))

        val existingEntry = Entry(
            id = "entry-1",
            content = originalSecretBytes,
            createdAt = 500L,
            modifiedAt = 500L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )
        every { repository.find("entry-1") } returns flowOf(existingEntry)

        coEvery { yubiKeyOathCredentialWriter.write(any()) } returns YubiKeyOathCredentialWriteResult(
            credentialId = "yubikey-cred-123",
            deviceId = "dev-456"
        )

        every { encryptionContext.decrypt(originalSecretBytes, EncryptionTag.EntryContent) } returns byteArrayOf(1, 2)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1, 2)) } returns sampleModel
        every { authenticatorClient.generateCodes(listOf(sampleModel), 1000uL) } returns listOf(
            AuthenticatorCodeResponseModel(
                currentCode = "123456",
                nextCode = "654321",
                entry = sampleModel
            )
        )

        coEvery {
            yubiKeyOathCredentialWriter.calculateCode("yubikey-cred-123", 1000L)
        } throws RuntimeException("YubiKey communication error")

        var thrown = false
        try {
            updater.migrateToYubiKeyTotp(sampleCommand)
        } catch (e: RuntimeException) {
            thrown = true
        }
        assertTrue("Expected RuntimeException", thrown)

        coVerify(exactly = 0) { repository.save(any()) }
    }
}

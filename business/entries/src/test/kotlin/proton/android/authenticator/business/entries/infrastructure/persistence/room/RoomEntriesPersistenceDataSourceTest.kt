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

package proton.android.authenticator.business.entries.infrastructure.persistence.room

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.shared.infrastructure.persistence.room.entities.entries.EntriesDao
import proton.android.authenticator.business.shared.infrastructure.persistence.room.entities.entries.EntryEntity
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag

class RoomEntriesPersistenceDataSourceTest {

    private val entriesDao: EntriesDao = mockk(relaxed = true)
    private val encryptionContext: EncryptionContext = mockk()
    private val encryptionContextProvider: EncryptionContextProvider = object : EncryptionContextProvider {
        override suspend fun <R> withEncryptionContext(
            encryptionKey: proton.android.authenticator.shared.crypto.domain.keys.EncryptionKey?,
            block: suspend EncryptionContext.() -> R
        ): R = encryptionContext.block()
    }
    private lateinit var dataSource: RoomEntriesPersistenceDataSource

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val testTagPrefix = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() {

        every { encryptionContext.encrypt(any(), any()) } answers {
            val bytes = firstArg<ByteArray>()
            EncryptedByteArray(testTagPrefix + bytes)
        }

        every { encryptionContext.decrypt(any(), any()) } answers {
            val encrypted = firstArg<EncryptedByteArray>()
            if (encrypted.array.size >= testTagPrefix.size &&
                encrypted.array.sliceArray(0 until testTagPrefix.size).contentEquals(testTagPrefix)
            ) {
                encrypted.array.copyOfRange(testTagPrefix.size, encrypted.array.size)
            } else {
                byteArrayOf()
            }
        }

        dataSource = RoomEntriesPersistenceDataSource(
            entriesDao = entriesDao,
            encryptionContextProvider = encryptionContextProvider
        )
    }

    @Test
    fun `when YubiKey row has missing credential id, observeAll falls back safely to LocalEncrypted`() = runTest {
        val corruptedEntity = EntryEntity(
            id = "corrupted-1",
            content = EncryptedByteArray(byteArrayOf(1, 2, 3)),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = true,
            position = 1,
            credentialBackendType = "yubikey_oath",
            hardwareCredentialId = null,
            hardwareDeviceId = "device-1",
            hardwareName = "Corrupted Entry",
            hardwareIssuer = "Proton",
            hardwareNote = null,
            hardwarePeriod = 30,
            hardwareAlgorithm = 0,
            hardwareDigits = 6
        )
        every { entriesDao.observeAll() } returns flowOf(listOf(corruptedEntity))

        val entries = dataSource.observeAll().first()

        assertEquals(1, entries.size)
        assertEquals("corrupted-1", entries[0].id)
        assertTrue(entries[0].credentialBackend is EntryCredentialBackend.LocalEncrypted)
    }

    @Test
    fun `when row has unknown backend type, observeAll falls back safely to LocalEncrypted`() = runTest {
        val unknownEntity = EntryEntity(
            id = "unknown-backend-1",
            content = EncryptedByteArray(byteArrayOf(1, 2, 3)),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = false,
            position = 2,
            credentialBackendType = "quantum_credential_backend",
            hardwareCredentialId = "some-id",
            hardwareDeviceId = null,
            hardwareName = null,
            hardwareIssuer = null,
            hardwareNote = null,
            hardwarePeriod = null,
            hardwareAlgorithm = null,
            hardwareDigits = null
        )
        every { entriesDao.observeAll() } returns flowOf(listOf(unknownEntity))

        val entries = dataSource.observeAll().first()

        assertEquals(1, entries.size)
        assertEquals("unknown-backend-1", entries[0].id)
        assertTrue(entries[0].credentialBackend is EntryCredentialBackend.LocalEncrypted)
    }

    @Test
    fun `when YubiKey row has invalid algorithm value, falls back to SHA1 without crashing`() = runTest {
        val entityWithInvalidAlg = EntryEntity(
            id = "invalid-alg-1",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = true,
            position = 3,
            credentialBackendType = "yubikey_oath",
            hardwareCredentialId = "valid-cred-id",
            hardwareDeviceId = "dev-1",
            hardwareName = "Valid Name",
            hardwareIssuer = "Issuer",
            hardwareNote = null,
            hardwarePeriod = 30,
            hardwareAlgorithm = 999,
            hardwareDigits = 6
        )
        every { entriesDao.observeById("invalid-alg-1") } returns flowOf(entityWithInvalidAlg)

        val entry = dataSource.byId("invalid-alg-1").first()

        assertEquals("invalid-alg-1", entry.id)
        val backend = entry.credentialBackend as EntryCredentialBackend.YubiKeyOath
        assertEquals(EntryAlgorithm.SHA1, backend.algorithm)
        assertEquals("valid-cred-id", backend.credentialId)
    }

    @Test
    fun `when byId observes row with null hardwareCredentialId, falls back to LocalEncrypted`() = runTest {
        val corruptedEntity = EntryEntity(
            id = "corrupted-by-id",
            content = EncryptedByteArray(byteArrayOf(4, 5, 6)),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackendType = "yubikey_oath",
            hardwareCredentialId = null,
            hardwareDeviceId = null,
            hardwareName = null,
            hardwareIssuer = null,
            hardwareNote = null,
            hardwarePeriod = null,
            hardwareAlgorithm = null,
            hardwareDigits = null
        )
        every { entriesDao.observeById("corrupted-by-id") } returns flowOf(corruptedEntity)

        val entry = dataSource.byId("corrupted-by-id").first()

        assertEquals("corrupted-by-id", entry.id)
        assertTrue(entry.credentialBackend is EntryCredentialBackend.LocalEncrypted)
    }

    @Test
    fun `when saving YubiKeyOath entry, encrypts metadata and writes null to plaintext columns`() =
        runTest {
            val yubiKeyBackend = EntryCredentialBackend.YubiKeyOath(
                credentialId = "test-cred-id",
                deviceId = "dev-123",
                name = "Test Account",
                issuer = "Proton",
                note = "Important Note",
                period = 30,
                algorithm = EntryAlgorithm.SHA1,
                digits = 6
            )
            val entry = Entry(
                id = "yubikey-entry-save",
                content = EncryptedByteArray(byteArrayOf()),
                createdAt = 1000L,
                modifiedAt = 2000L,
                isDeleted = false,
                isSynced = false,
                position = 5,
                credentialBackend = yubiKeyBackend
            )

            dataSource.insert(entry)

            val slot = slot<EntryEntity>()
            coVerify(exactly = 1) { entriesDao.upsert(capture(slot)) }

            val entity = slot.captured
            assertEquals("yubikey-entry-save", entity.id)
            assertEquals("yubikey_oath", entity.credentialBackendType)
            assertEquals("test-cred-id", entity.hardwareCredentialId)
            assertEquals("dev-123", entity.hardwareDeviceId)

            // Ensure zero plaintext leaks
            assertNull(entity.hardwareName)
            assertNull(entity.hardwareIssuer)
            assertNull(entity.hardwareNote)

            // Verify encrypted content contains serialized metadata
            val decryptedBytes = encryptionContext.decrypt(entity.content, EncryptionTag.EntryContent)
            val metadata = json.decodeFromString<HardwareEntryMetadata>(decryptedBytes.decodeToString())
            assertEquals("Test Account", metadata.name)
            assertEquals("Proton", metadata.issuer)
            assertEquals("Important Note", metadata.note)
        }

    @Test
    fun `when loading YubiKeyOath entry with encrypted metadata in content, decrypts and populates name issuer note`() =
        runTest {
            val metadata = HardwareEntryMetadata(
                name = "Encrypted Account",
                issuer = "Encrypted Issuer",
                note = "Secret Hardware Note"
            )
            val serialized = json.encodeToString(HardwareEntryMetadata.serializer(), metadata).encodeToByteArray()
            val encryptedContent = EncryptedByteArray(testTagPrefix + serialized)

            val entity = EntryEntity(
                id = "encrypted-hardware-entry",
                content = encryptedContent,
                createdAt = 1000L,
                modifiedAt = 2000L,
                isDeleted = false,
                isSynced = false,
                position = 1,
                credentialBackendType = "yubikey_oath",
                hardwareCredentialId = "cred-xyz",
                hardwareDeviceId = "device-xyz",
                hardwareName = null,
                hardwareIssuer = null,
                hardwareNote = null,
                hardwarePeriod = 30,
                hardwareAlgorithm = 0,
                hardwareDigits = 6
            )
            every { entriesDao.observeById("encrypted-hardware-entry") } returns flowOf(entity)

            val entry = dataSource.byId("encrypted-hardware-entry").first()

            assertEquals("encrypted-hardware-entry", entry.id)
            assertTrue(entry.credentialBackend is EntryCredentialBackend.YubiKeyOath)
            val backend = entry.credentialBackend as EntryCredentialBackend.YubiKeyOath
            assertEquals("cred-xyz", backend.credentialId)
            assertEquals("device-xyz", backend.deviceId)
            assertEquals("Encrypted Account", backend.name)
            assertEquals("Encrypted Issuer", backend.issuer)
            assertEquals("Secret Hardware Note", backend.note)
        }

    @Test
    fun `when loading legacy YubiKeyOath entry with empty content, falls back gracefully to plaintext columns`() =
        runTest {
            val legacyEntity = EntryEntity(
                id = "legacy-hardware-entry",
                content = EncryptedByteArray(byteArrayOf()),
                createdAt = 1000L,
                modifiedAt = 2000L,
                isDeleted = false,
                isSynced = false,
                position = 2,
                credentialBackendType = "yubikey_oath",
                hardwareCredentialId = "legacy-cred-id",
                hardwareDeviceId = "legacy-device",
                hardwareName = "Legacy Plaintext Name",
                hardwareIssuer = "Legacy Plaintext Issuer",
                hardwareNote = "Legacy Plaintext Note",
                hardwarePeriod = 30,
                hardwareAlgorithm = 0,
                hardwareDigits = 6
            )
            every { entriesDao.observeById("legacy-hardware-entry") } returns flowOf(legacyEntity)

            val entry = dataSource.byId("legacy-hardware-entry").first()

            assertEquals("legacy-hardware-entry", entry.id)
            assertTrue(entry.credentialBackend is EntryCredentialBackend.YubiKeyOath)
            val backend = entry.credentialBackend as EntryCredentialBackend.YubiKeyOath
            assertEquals("legacy-cred-id", backend.credentialId)
            assertEquals("legacy-device", backend.deviceId)
            assertEquals("Legacy Plaintext Name", backend.name)
            assertEquals("Legacy Plaintext Issuer", backend.issuer)
            assertEquals("Legacy Plaintext Note", backend.note)
        }

    @Test
    fun `when loading legacy YubiKeyOath entry with 0-byte decrypted content, falls back gracefully`() =
        runTest {
            // Simulates old behavior where content was encrypt(ByteArray(0))
            // Decrypt returns byteArrayOf()
            val emptyEncryptedContent = EncryptedByteArray(testTagPrefix)
            val legacyEntity = EntryEntity(
                id = "legacy-zero-bytes-entry",
                content = emptyEncryptedContent,
                createdAt = 1000L,
                modifiedAt = 2000L,
                isDeleted = false,
                isSynced = false,
                position = 3,
                credentialBackendType = "yubikey_oath",
                hardwareCredentialId = "zero-byte-cred",
                hardwareDeviceId = null,
                hardwareName = "Zero Byte Plaintext Name",
                hardwareIssuer = "Zero Byte Plaintext Issuer",
                hardwareNote = null,
                hardwarePeriod = 30,
                hardwareAlgorithm = 0,
                hardwareDigits = 6
            )
            every { entriesDao.observeById("legacy-zero-bytes-entry") } returns flowOf(legacyEntity)

            val entry = dataSource.byId("legacy-zero-bytes-entry").first()

            assertEquals("legacy-zero-bytes-entry", entry.id)
            assertTrue(entry.credentialBackend is EntryCredentialBackend.YubiKeyOath)
            val backend = entry.credentialBackend as EntryCredentialBackend.YubiKeyOath
            assertEquals("Zero Byte Plaintext Name", backend.name)
            assertEquals("Zero Byte Plaintext Issuer", backend.issuer)
            assertNull(backend.note)
        }

    @Test
    fun `when saving LocalEncrypted entry, preserves content and sets hardware columns to null`() = runTest {
        val localEntry = Entry(
            id = "local-entry-1",
            content = EncryptedByteArray(byteArrayOf(99, 100)),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = true,
            position = 0,
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )

        dataSource.insert(localEntry)

        val slot = slot<EntryEntity>()
        coVerify(exactly = 1) { entriesDao.upsert(capture(slot)) }

        val entity = slot.captured
        assertEquals("local-entry-1", entity.id)
        assertEquals("local_encrypted", entity.credentialBackendType)
        assertEquals(EncryptedByteArray(byteArrayOf(99, 100)), entity.content)
        assertNull(entity.hardwareCredentialId)
        assertNull(entity.hardwareName)
        assertNull(entity.hardwareIssuer)
        assertNull(entity.hardwareNote)
    }

    @Test
    fun `insertAll encrypts all YubiKeyOath entries and writes null to plaintext columns`() = runTest {
        val yubiKey1 = Entry(
            id = "batch-yubi-1",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = false,
            position = 0,
            credentialBackend = EntryCredentialBackend.YubiKeyOath(
                credentialId = "batch-cred-1",
                deviceId = null,
                name = "Account 1",
                issuer = "Issuer 1",
                note = null,
                period = 30,
                algorithm = EntryAlgorithm.SHA1,
                digits = 6
            )
        )
        val yubiKey2 = Entry(
            id = "batch-yubi-2",
            content = EncryptedByteArray(byteArrayOf()),
            createdAt = 1000L,
            modifiedAt = 2000L,
            isDeleted = false,
            isSynced = false,
            position = 1,
            credentialBackend = EntryCredentialBackend.YubiKeyOath(
                credentialId = "batch-cred-2",
                deviceId = null,
                name = "Account 2",
                issuer = "Issuer 2",
                note = null,
                period = 30,
                algorithm = EntryAlgorithm.SHA1,
                digits = 6
            )
        )

        dataSource.insertAll(listOf(yubiKey1, yubiKey2))

        val slot = slot<List<EntryEntity>>()
        coVerify(exactly = 1) { entriesDao.upsertAll(capture(slot)) }

        val entities = slot.captured
        assertEquals(2, entities.size)
        assertNull(entities[0].hardwareName)
        assertNull(entities[0].hardwareIssuer)
        assertNull(entities[1].hardwareName)
        assertNull(entities[1].hardwareIssuer)

        val meta1 = json.decodeFromString<HardwareEntryMetadata>(
            encryptionContext.decrypt(entities[0].content, EncryptionTag.EntryContent).decodeToString()
        )
        val meta2 = json.decodeFromString<HardwareEntryMetadata>(
            encryptionContext.decrypt(entities[1].content, EncryptionTag.EntryContent).decodeToString()
        )
        assertEquals("Account 1", meta1.name)
        assertEquals("Account 2", meta2.name)
    }
}

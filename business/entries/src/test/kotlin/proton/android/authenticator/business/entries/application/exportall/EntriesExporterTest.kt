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

package proton.android.authenticator.business.entries.application.exportall

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.AuthenticatorEntryType
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.shared.domain.infrastructure.files.FileWriter
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag

class EntriesExporterTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = object : AppDispatchers {
        override val main: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
    }

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val encryptionContext: EncryptionContext = mockk()
    private val encryptionContextProvider: EncryptionContextProvider = TestEncryptionContextProvider(encryptionContext)
    private val fileWriter: FileWriter = mockk(relaxed = true)
    private val repository: EntriesRepository = mockk()
    private val destinationUri: Uri = mockk()

    private lateinit var exporter: EntriesExporter

    private val localEntry1 = Entry(
        id = "entry-1",
        content = EncryptedByteArray(byteArrayOf(1, 2, 3)),
        createdAt = 100L,
        modifiedAt = 100L,
        isDeleted = false,
        isSynced = true,
        position = 1,
        credentialBackend = EntryCredentialBackend.LocalEncrypted
    )

    private val localEntry2 = Entry(
        id = "entry-2",
        content = EncryptedByteArray(byteArrayOf(4, 5, 6)),
        createdAt = 200L,
        modifiedAt = 200L,
        isDeleted = false,
        isSynced = true,
        position = 2,
        credentialBackend = EntryCredentialBackend.LocalEncrypted
    )

    private val hardwareEntry = Entry(
        id = "entry-hw",
        content = EncryptedByteArray(byteArrayOf()),
        createdAt = 300L,
        modifiedAt = 300L,
        isDeleted = false,
        isSynced = false,
        position = 3,
        credentialBackend = EntryCredentialBackend.YubiKeyOath(
            credentialId = "yubi-id",
            deviceId = "yubi-dev",
            name = "HW Token",
            issuer = "HW Issuer",
            note = null,
            period = 30,
            algorithm = EntryAlgorithm.SHA1,
            digits = 6
        )
    )

    private val deletedEntry = Entry(
        id = "entry-deleted",
        content = EncryptedByteArray(byteArrayOf(7, 8, 9)),
        createdAt = 400L,
        modifiedAt = 400L,
        isDeleted = true,
        isSynced = false,
        position = 4,
        credentialBackend = EntryCredentialBackend.LocalEncrypted
    )

    private val entryModel1 = AuthenticatorEntryModel(
        id = "entry-1",
        name = "Entry 1",
        issuer = "Issuer 1",
        note = null,
        secret = "SECRET1",
        uri = "otpauth://totp/Entry1?secret=SECRET1",
        period = 30u,
        entryType = AuthenticatorEntryType.TOTP
    )

    private val entryModel2 = AuthenticatorEntryModel(
        id = "entry-2",
        name = "Entry 2",
        issuer = "Issuer 2",
        note = null,
        secret = "SECRET2",
        uri = "otpauth://totp/Entry2?secret=SECRET2",
        period = 30u,
        entryType = AuthenticatorEntryType.TOTP
    )

    @Before
    fun setUp() {
        exporter = EntriesExporter(
            appDispatchers = appDispatchers,
            authenticatorClient = authenticatorClient,
            encryptionContextProvider = encryptionContextProvider,
            fileWriter = fileWriter,
            repository = repository
        )
    }

    @Test
    fun `export rejects null password with ExportPasswordRequiredError`() = runTest(testDispatcher) {
        every { repository.findAll() } returns flowOf(listOf(localEntry1))
        every { encryptionContext.decrypt(localEntry1.content, EncryptionTag.EntryContent) } returns byteArrayOf(1)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1)) } returns entryModel1

        var thrown = false
        try {
            exporter.export(destinationUri, password = null)
        } catch (e: ExportPasswordRequiredError) {
            thrown = true
        }
        org.junit.Assert.assertTrue("Should throw ExportPasswordRequiredError", thrown)
        coVerify(exactly = 0) { fileWriter.write(any(), any()) }
    }

    @Test
    fun `export rejects blank password with ExportPasswordRequiredError`() = runTest(testDispatcher) {
        every { repository.findAll() } returns flowOf(listOf(localEntry1))
        every { encryptionContext.decrypt(localEntry1.content, EncryptionTag.EntryContent) } returns byteArrayOf(1)
        every { authenticatorClient.deserializeEntry(byteArrayOf(1)) } returns entryModel1

        var thrown = false
        try {
            exporter.export(destinationUri, password = "   ")
        } catch (e: ExportPasswordRequiredError) {
            thrown = true
        }
        org.junit.Assert.assertTrue("Should throw ExportPasswordRequiredError", thrown)
        coVerify(exactly = 0) { fileWriter.write(any(), any()) }
    }

    @Test
    fun `export filters out hardware-backed and deleted entries and exports only local encrypted entries`() =
        runTest(testDispatcher) {
            every { repository.findAll() } returns flowOf(
                listOf(localEntry1, hardwareEntry, localEntry2, deletedEntry)
            )

            every { encryptionContext.decrypt(localEntry1.content, EncryptionTag.EntryContent) } returns byteArrayOf(1)
            every { encryptionContext.decrypt(localEntry2.content, EncryptionTag.EntryContent) } returns byteArrayOf(2)
            every { authenticatorClient.deserializeEntry(byteArrayOf(1)) } returns entryModel1
            every { authenticatorClient.deserializeEntry(byteArrayOf(2)) } returns entryModel2

            val modelsSlot = slot<List<AuthenticatorEntryModel>>()
            every {
                authenticatorClient.exportEntriesWithPassword(capture(modelsSlot), "valid-password")
            } returns "exported_json_content"

            val exportedCount = exporter.export(destinationUri, password = "valid-password")

            assertEquals(2, exportedCount)

            val exportedModels = modelsSlot.captured
            assertEquals(2, exportedModels.size)
            assertEquals(listOf(entryModel1, entryModel2), exportedModels)

            coVerify(exactly = 1) { fileWriter.write(destinationUri, "exported_json_content") }

            // Verify hardwareEntry and deletedEntry were never decrypted or deserialized
            coVerify(exactly = 0) { encryptionContext.decrypt(hardwareEntry.content, any()) }
            coVerify(exactly = 0) { encryptionContext.decrypt(deletedEntry.content, any()) }
        }
}

private class TestEncryptionContextProvider(
    private val context: EncryptionContext
) : EncryptionContextProvider {
    override suspend fun <R> withEncryptionContext(
        encryptionKey: proton.android.authenticator.shared.crypto.domain.keys.EncryptionKey?,
        block: suspend EncryptionContext.() -> R
    ): R = context.block()
}

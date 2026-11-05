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

package proton.android.authenticator.business.entries.application.importall

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.AuthenticatorEntryType
import proton.android.authenticator.business.entries.application.shared.constants.EntryConstants
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryImportType
import proton.android.authenticator.business.shared.domain.infrastructure.files.FileReader
import proton.android.authenticator.commonrust.AuthenticatorImportError
import proton.android.authenticator.commonrust.AuthenticatorImportResult
import proton.android.authenticator.commonrust.AuthenticatorImporterInterface
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.domain.providers.MimeTypeProvider
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import proton.android.authenticator.shared.common.domain.scanners.QrScanner
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag

class EntriesImporterTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = object : AppDispatchers {
        override val main: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
    }

    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk()
    private val authenticatorImporter: AuthenticatorImporterInterface = mockk()
    private val encryptionContext: EncryptionContext = mockk()
    private val encryptionContextProvider: EncryptionContextProvider = TestEncryptionContextProvider(encryptionContext)
    private val fileReader: FileReader = mockk()
    private val mimeTypeProvider: MimeTypeProvider = mockk()
    private val qrScanner: QrScanner = mockk()
    private val repository: EntriesRepository = mockk()
    private val timeProvider: TimeProvider = mockk()

    private lateinit var importer: EntriesImporter

    private val sampleModel1 = AuthenticatorEntryModel(
        id = "id-1",
        name = "user@example.com",
        issuer = "Google",
        secret = "JBSWY3DPEHPK3PXP",
        uri = "otpauth://totp/Google:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Google",
        period = 30u,
        entryType = AuthenticatorEntryType.TOTP,
        note = null
    )

    private val sampleModel2 = AuthenticatorEntryModel(
        id = "id-2",
        name = "admin@github.com",
        issuer = "GitHub",
        secret = "KRSXG5CTMVRXEZLU",
        uri = "otpauth://totp/GitHub:admin@github.com?secret=KRSXG5CTMVRXEZLU&issuer=GitHub",
        period = 30u,
        entryType = AuthenticatorEntryType.TOTP,
        note = null
    )

    @Before
    fun setUp() {
        importer = EntriesImporter(
            appDispatchers = appDispatchers,
            authenticatorClient = authenticatorClient,
            authenticatorImporter = authenticatorImporter,
            encryptionContextProvider = encryptionContextProvider,
            fileReader = fileReader,
            mimeTypeProvider = mimeTypeProvider,
            qrScanner = qrScanner,
            repository = repository,
            timeProvider = timeProvider
        )

        coEvery { repository.searchMaxPosition() } returns 0
        coEvery { repository.saveAll(any()) } returns Unit
        every { timeProvider.currentSeconds() } returns 1700000000L
        every { authenticatorClient.serializeEntry(any()) } returns byteArrayOf(1, 2, 3)
        coEvery { encryptionContext.encrypt(any(), EncryptionTag.EntryContent) } returns EncryptedByteArray(byteArrayOf(9, 9, 9))
    }

    @Test
    fun `import from bytes with otpauth-migration text payload bypasses qr re-scan`() = runTest(testDispatcher) {
        val payload = "otpauth-migration://offline?data=CjEKCkdPT0dMRV9LRVkSEXVzZXJAZXhhbXBsZS5jb20aBmdvb2dsZSIBMSoBMDIBSAEYASAA"
        val bytes = payload.toByteArray(Charsets.UTF_8).toList()

        every {
            authenticatorImporter.importFromGoogleQr(payload)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel1), errors = emptyList())

        val count = importer.import(contentBytes = bytes, importType = EntryImportType.Google)

        assertEquals(1, count)
        coVerify(exactly = 0) { qrScanner.scan(any<ByteArray>()) }
        coVerify(exactly = 1) { authenticatorImporter.importFromGoogleQr(payload) }
        coVerify(exactly = 1) { repository.saveAll(any()) }
    }

    @Test
    fun `import from bytes with raw image invokes qr scanner and decodes entries`() = runTest(testDispatcher) {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // PNG header
        val decodedQrText = "otpauth-migration://offline?data=some_protobuf_data"

        coEvery { qrScanner.scan(bytes = imageBytes) } returns decodedQrText
        every {
            authenticatorImporter.importFromGoogleQr(decodedQrText)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel1), errors = emptyList())

        val count = importer.import(contentBytes = imageBytes.toList(), importType = EntryImportType.Google)

        assertEquals(1, count)
        coVerify(exactly = 1) { qrScanner.scan(bytes = imageBytes) }
        coVerify(exactly = 1) { authenticatorImporter.importFromGoogleQr(decodedQrText) }
    }

    @Test
    fun `import from bytes with otpauth URI parses standard entry`() = runTest(testDispatcher) {
        val otpauthUri = "otpauth://totp/Google:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Google"
        val bytes = otpauthUri.toByteArray(Charsets.UTF_8).toList()

        every { authenticatorClient.entryFromUri(otpauthUri) } returns sampleModel1

        val count = importer.import(contentBytes = bytes, importType = EntryImportType.Google)

        assertEquals(1, count)
        coVerify(exactly = 0) { qrScanner.scan(any<ByteArray>()) }
        coVerify(exactly = 1) { authenticatorClient.entryFromUri(otpauthUri) }
    }

    @Test
    fun `import from multi-page QR uris scans all pages and aggregates entries`() = runTest(testDispatcher) {
        val uri1: Uri = mockk()
        val uri2: Uri = mockk()
        every { uri1.toString() } returns "content://media/image_1.png"
        every { uri2.toString() } returns "content://media/image_2.png"

        // Neither is a text file
        coEvery { fileReader.readText("content://media/image_1.png") } throws IllegalArgumentException("Not text")
        coEvery { fileReader.readText("content://media/image_2.png") } throws IllegalArgumentException("Not text")

        val payload1 = "otpauth-migration://offline?data=page1_data"
        val payload2 = "otpauth-migration://offline?data=page2_data"

        coEvery { qrScanner.scan(uri1) } returns payload1
        coEvery { qrScanner.scan(uri2) } returns payload2

        every {
            authenticatorImporter.importFromGoogleQr(payload1)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel1), errors = emptyList())
        every {
            authenticatorImporter.importFromGoogleQr(payload2)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel2), errors = emptyList())

        val savedEntriesSlot = slot<List<Entry>>()
        coEvery { repository.saveAll(capture(savedEntriesSlot)) } returns Unit

        val count = importer.import(
            contentUris = listOf(uri1, uri2),
            importType = EntryImportType.Google,
            password = null
        )

        assertEquals(2, count)
        val saved = savedEntriesSlot.captured
        assertEquals(2, saved.size)
        assertEquals("id-1", saved[0].id)
        assertEquals("id-2", saved[1].id)
        assertEquals(EntryConstants.POSITION_INCREMENT, saved[0].position)
        assertEquals(EntryConstants.POSITION_INCREMENT * 2, saved[1].position)
    }

    @Test
    fun `import from text file uri reads otpauth-migration directly without image scan`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        every { uri.toString() } returns "content://files/export.txt"

        val fileContent = "otpauth-migration://offline?data=text_file_data"
        coEvery { fileReader.readText("content://files/export.txt") } returns fileContent

        every {
            authenticatorImporter.importFromGoogleQr(fileContent)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel1), errors = emptyList())

        val count = importer.import(
            contentUris = listOf(uri),
            importType = EntryImportType.Google,
            password = null
        )

        assertEquals(1, count)
        coVerify(exactly = 0) { qrScanner.scan(any<Uri>()) }
        coVerify(exactly = 1) { authenticatorImporter.importFromGoogleQr(fileContent) }
    }

    @Test
    fun `import multi-line text with mix of otpauth-migration and otpauth URIs`() = runTest(testDispatcher) {
        val uri: Uri = mockk()
        every { uri.toString() } returns "content://files/mixed_export.txt"

        val line1 = "otpauth-migration://offline?data=mig_1"
        val line2 = "otpauth://totp/GitHub:admin@github.com?secret=KRSXG5CTMVRXEZLU&issuer=GitHub"
        val combined = "$line1\n$line2"

        coEvery { fileReader.readText("content://files/mixed_export.txt") } returns combined

        every {
            authenticatorImporter.importFromGoogleQr(line1)
        } returns AuthenticatorImportResult(entries = listOf(sampleModel1), errors = emptyList())
        every { authenticatorClient.entryFromUri(line2) } returns sampleModel2

        val count = importer.import(
            contentUris = listOf(uri),
            importType = EntryImportType.Google,
            password = null
        )

        assertEquals(2, count)
        coVerify(exactly = 1) { authenticatorImporter.importFromGoogleQr(line1) }
        coVerify(exactly = 1) { authenticatorClient.entryFromUri(line2) }
    }

    @Test
    fun `import unsupported type from bytes throws IllegalArgumentException`() = runTest(testDispatcher) {
        assertThrows(IllegalArgumentException::class.java) {
            runTest(testDispatcher) {
                importer.import(contentBytes = listOf(1, 2, 3), importType = EntryImportType.Bitwarden)
            }
        }
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

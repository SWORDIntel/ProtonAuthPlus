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
import kotlinx.coroutines.withContext
import proton.android.authenticator.business.entries.application.create.CreateEntryCommand
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialWriter
import proton.android.authenticator.business.entries.application.shared.constants.EntryConstants
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.entries.domain.EntryImportType
import proton.android.authenticator.business.shared.domain.infrastructure.files.FileReader
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.commonrust.AuthenticatorImporterInterface
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.domain.models.MimeType
import proton.android.authenticator.shared.common.domain.providers.MimeTypeProvider
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import proton.android.authenticator.shared.common.domain.scanners.QrScanner
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag
import javax.inject.Inject

internal class EntriesImporter @Inject constructor(
    private val appDispatchers: AppDispatchers,
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val authenticatorImporter: AuthenticatorImporterInterface,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val fileReader: FileReader,
    private val mimeTypeProvider: MimeTypeProvider,
    private val qrScanner: QrScanner,
    private val repository: EntriesRepository,
    private val timeProvider: TimeProvider,
    private val yubiKeyOathCredentialWriter: YubiKeyOathCredentialWriter
) {
    internal suspend fun import(contentBytes: List<Byte>, importType: EntryImportType): Int =
        saveEntries(getEntries(contentBytes, importType))

    private suspend fun getEntries(
        contentBytes: List<Byte>,
        importType: EntryImportType
    ): List<AuthenticatorEntryModel> = when (importType) {
        EntryImportType.Google -> {
            val bytes = contentBytes.toByteArray()
            val textContent = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
            val qrText = if (textContent.startsWith("otpauth-migration://", ignoreCase = true) ||
                textContent.startsWith("otpauth://", ignoreCase = true)
            ) {
                textContent
            } else {
                qrScanner.scan(bytes = bytes).orEmpty()
            }
            parseGoogleExportContent(qrText)
        }

        EntryImportType.Aegis,
        EntryImportType.Authy,
        EntryImportType.Bitwarden,
        EntryImportType.Ente,
        EntryImportType.LastPass,
        EntryImportType.Microsoft,
        EntryImportType.ProtonAuthenticator,
        EntryImportType.ProtonPass,
        EntryImportType.TwoFas -> {
            throw IllegalArgumentException("Unsupported bytes import type: $importType")
        }
    }

    internal suspend fun import(
        contentUris: List<Uri>,
        importType: EntryImportType,
        password: String?
    ): Int = saveEntries(getEntries(contentUris, importType, password))

    private suspend fun getEntries(
        contentUris: List<Uri>,
        importType: EntryImportType,
        password: String?
    ): List<AuthenticatorEntryModel> = contentUris.flatMap { contentUri ->
        var content = ""
        var contentBinary: ByteArray = byteArrayOf()

        when (importType) {
            EntryImportType.ProtonPass ->
                contentBinary = fileReader.readBinary(contentUri.toString(), MAX_ZIP_SIZE)

            EntryImportType.Google -> {
                val textCandidate = runCatching { fileReader.readText(contentUri.toString()) }.getOrNull()
                if (textCandidate != null && (textCandidate.contains("otpauth-migration://") || textCandidate.contains("otpauth://"))) {
                    content = textCandidate
                } else {
                    content = qrScanner.scan(contentUri).orEmpty()
                }
            }

            else -> content = fileReader.readText(contentUri.toString())
        }

        getEntriesFromContent(importType, contentUri, content, contentBinary, password)
    }

    private suspend fun getEntriesFromContent(
        importType: EntryImportType,
        contentUri: Uri,
        content: String,
        contentBinary: ByteArray,
        password: String?
    ): List<AuthenticatorEntryModel> = withContext(appDispatchers.default) {
        if (importType == EntryImportType.Google) {
            return@withContext parseGoogleExportContent(content)
        }

        when (importType) {
            EntryImportType.Google -> error("Handled above")

            EntryImportType.Aegis -> {
                authenticatorImporter.importFromAegisJson(content, password)
            }

            EntryImportType.Bitwarden -> {
                mimeTypeProvider.getFileMimeType(contentUri.toString()).let { mimeType ->
                    when (mimeType) {
                        MimeType.CommaSeparatedValues,
                        MimeType.Csv -> authenticatorImporter.importFromBitwardenCsv(content)

                        MimeType.Json -> authenticatorImporter.importFromBitwardenJson(content)
                        MimeType.All,
                        MimeType.Binary,
                        MimeType.Image,
                        MimeType.Text,
                        MimeType.Zip -> {
                            throw IllegalArgumentException("Unsupported Bitwarden file type: $mimeType")
                        }
                    }
                }
            }

            EntryImportType.Ente -> {
                authenticatorImporter.importFromEnteTxt(content)
            }

            EntryImportType.LastPass -> {
                authenticatorImporter.importFromLastpassJson(content)
            }

            EntryImportType.ProtonAuthenticator -> {
                if (password == null) {
                    authenticatorImporter.importFromProtonAuthenticator(content)
                } else {
                    authenticatorImporter.importFromProtonAuthenticatorWithPassword(content, password)
                }
            }

            EntryImportType.ProtonPass -> {
                authenticatorImporter.importFromPassZip(contentBinary)
            }

            EntryImportType.TwoFas -> {
                authenticatorImporter.importFrom2fas(content, password)
            }

            EntryImportType.Authy,
            EntryImportType.Microsoft -> {
                throw IllegalArgumentException("Unsupported import type: $importType")
            }
        }.let { result ->
            if (result.errors.isNotEmpty()) {
                AuthenticatorLogger.w(TAG, "Found ${result.errors.size} errors on import from $importType")
                for (error in result.errors) {
                    AuthenticatorLogger.w(TAG, "Import error: [context=${error.context}] [message=${error.message}]")
                }
            }
            result.entries
        }
    }

    private fun parseGoogleExportContent(content: String): List<AuthenticatorEntryModel> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return authenticatorImporter.importFromGoogleQr("").entries
        }

        val lines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val entries = mutableListOf<AuthenticatorEntryModel>()
        var foundAnyValid = false

        for (line in lines) {
            when {
                line.startsWith("otpauth-migration://", ignoreCase = true) -> {
                    foundAnyValid = true
                    val result = authenticatorImporter.importFromGoogleQr(line)
                    if (result.errors.isNotEmpty()) {
                        AuthenticatorLogger.w(TAG, "Found ${result.errors.size} errors on import from Google QR")
                        for (error in result.errors) {
                            AuthenticatorLogger.w(TAG, "Import error: [context=${error.context}] [message=${error.message}]")
                        }
                    }
                    entries.addAll(result.entries)
                }

                line.startsWith("otpauth://", ignoreCase = true) -> {
                    foundAnyValid = true
                    runCatching {
                        authenticatorClient.entryFromUri(line)
                    }.onSuccess { model ->
                        entries.add(model)
                    }.onFailure { error ->
                        AuthenticatorLogger.w(TAG, error, "Failed to parse otpauth URI: $line")
                    }
                }

                else -> {
                    val result = authenticatorImporter.importFromGoogleQr(line)
                    foundAnyValid = true
                    if (result.errors.isNotEmpty()) {
                        AuthenticatorLogger.w(TAG, "Found ${result.errors.size} errors on import from Google QR")
                        for (error in result.errors) {
                            AuthenticatorLogger.w(TAG, "Import error: [context=${error.context}] [message=${error.message}]")
                        }
                    }
                    entries.addAll(result.entries)
                }
            }
        }

        if (!foundAnyValid && entries.isEmpty()) {
            return authenticatorImporter.importFromGoogleQr(trimmed).entries
        }

        return entries
    }

    private suspend fun saveEntries(entryModels: List<AuthenticatorEntryModel>): Int {
        if (entryModels.isEmpty()) return 0

        val commands = entryModels.map { model ->
            val totpParams = authenticatorClient.getTotpParams(entry = model)
            CreateEntryCommand.FromYubiKeyTotp(
                name = model.name,
                secret = model.secret,
                issuer = model.issuer,
                period = model.period.toInt(),
                digits = totpParams.digits.toInt(),
                algorithm = EntryAlgorithm.from(totpParams.algorithm.ordinal),
                note = model.note
            )
        }

        val writeResults = yubiKeyOathCredentialWriter.writeBatch(commands)
        var position = repository.searchMaxPosition()

        return encryptionContextProvider.withEncryptionContext {
            val emptyContent = encrypt(ByteArray(size = 0), EncryptionTag.EntryContent)
            writeResults.mapIndexed { index, (command, writeResult) ->
                position += EntryConstants.POSITION_INCREMENT
                val currentSeconds = timeProvider.currentSeconds()
                val originalId = entryModels.getOrNull(index)?.id ?: java.util.UUID.randomUUID().toString()

                Entry(
                    id = originalId,
                    content = emptyContent,
                    createdAt = currentSeconds,
                    modifiedAt = currentSeconds,
                    isDeleted = false,
                    isSynced = false,
                    position = position,
                    credentialBackend = EntryCredentialBackend.YubiKeyOath(
                        credentialId = writeResult.credentialId,
                        deviceId = writeResult.deviceId,
                        name = command.name,
                        issuer = command.issuer,
                        note = command.note,
                        period = command.period,
                        algorithm = command.algorithm,
                        digits = command.digits
                    )
                )
            }
        }
            .also { entries -> repository.saveAll(entries) }
            .let(List<Entry>::size)
    }

    private companion object {

        private const val TAG = "EntriesImporter"

        private const val MAX_ZIP_SIZE = 10 * 1_024 * 1_024

    }

}

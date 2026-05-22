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

import kotlinx.coroutines.flow.first
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialWriter
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag
import javax.inject.Inject

internal class EntryUpdater @Inject constructor(
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val yubiKeyOathCredentialWriter: YubiKeyOathCredentialWriter,
    private val repository: EntriesRepository,
    private val timeProvider: TimeProvider
) {

    internal suspend fun update(
        id: String,
        position: Int,
        model: AuthenticatorEntryModel
    ) {
        authenticatorClient.serializeEntry(model)
            .let { decryptedModelContent ->
                encryptionContextProvider.withEncryptionContext {
                    encrypt(decryptedModelContent, EncryptionTag.EntryContent)
                }
            }
            .let { encryptedContent ->
                Entry(
                    id = id,
                    content = encryptedContent,
                    createdAt = repository.find(id = id).first().createdAt,
                    modifiedAt = timeProvider.currentSeconds(),
                    isDeleted = false,
                    isSynced = false,
                    position = position
                )
            }
            .also { updatedEntry ->
                repository.save(updatedEntry)
            }
    }

    internal suspend fun migrateToYubiKeyTotp(command: UpdateEntryCommand.ToYubiKeyTotp) {
        val existingEntry = repository.find(id = command.id).first()
        require(existingEntry.credentialBackend == EntryCredentialBackend.LocalEncrypted) {
            "Only local encrypted entries can be migrated to YubiKey"
        }

        val writeResult = yubiKeyOathCredentialWriter.write(command.toCreateCommand())

        encryptionContextProvider.withEncryptionContext {
            encrypt(ByteArray(size = 0), EncryptionTag.EntryContent)
        }
            .let { encryptedContent ->
                existingEntry.copy(
                    content = encryptedContent,
                    modifiedAt = timeProvider.currentSeconds(),
                    isSynced = false,
                    position = command.position,
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
            .also { updatedEntry ->
                repository.save(updatedEntry)
            }
    }

}

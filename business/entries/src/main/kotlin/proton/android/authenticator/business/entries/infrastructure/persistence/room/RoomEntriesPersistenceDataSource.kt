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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.shared.domain.infrastructure.persistence.PersistenceDataSource
import proton.android.authenticator.business.shared.infrastructure.persistence.room.entities.entries.EntriesDao
import proton.android.authenticator.business.shared.infrastructure.persistence.room.entities.entries.EntryEntity
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag
import javax.inject.Inject

internal class RoomEntriesPersistenceDataSource @Inject constructor(
    private val entriesDao: EntriesDao,
    private val encryptionContextProvider: EncryptionContextProvider
) : PersistenceDataSource<Entry> {

    override fun observeAll(): Flow<List<Entry>> = entriesDao.observeAll()
        .map { entryEntities ->
            encryptionContextProvider.withEncryptionContext {
                entryEntities.mapNotNull { entity ->
                    runCatching { entity.toDomain(this) }
                        .onFailure { throwable ->
                            AuthenticatorLogger.w(TAG, throwable, "Failed to map EntryEntity with id ${entity.id} to domain")
                        }
                        .getOrNull()
                }
            }
        }

    override fun byId(id: String): Flow<Entry> = entriesDao.observeById(id)
        .map { entity ->
            encryptionContextProvider.withEncryptionContext {
                entity.toDomain(this)
            }
        }

    override suspend fun delete(entry: Entry) {
        entriesDao.delete(entry.toDeleteEntity())
    }

    override suspend fun deleteAll(entries: List<Entry>) {
        val entryEntities = entries.map(Entry::toDeleteEntity)
        entriesDao.deleteAll(entryEntities)
    }

    override suspend fun insert(entry: Entry) {
        val entity = encryptionContextProvider.withEncryptionContext {
            entry.toEntity(this)
        }
        entriesDao.upsert(entity)
    }

    override suspend fun insertAll(entries: List<Entry>) {
        val entryEntities = encryptionContextProvider.withEncryptionContext {
            entries.map { entry -> entry.toEntity(this) }
        }
        entriesDao.upsertAll(entryEntities)
    }

    override suspend fun searchMaxPosition(): Int = entriesDao.searchMaxPosition()

}

private fun Entry.toEntity(encryptionContext: EncryptionContext): EntryEntity = when (val backend = credentialBackend) {
    EntryCredentialBackend.LocalEncrypted -> EntryEntity(
        id = id,
        content = content,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted,
        isSynced = isSynced,
        position = position,
        credentialBackendType = BACKEND_LOCAL_ENCRYPTED,
        hardwareCredentialId = null,
        hardwareDeviceId = null,
        hardwareName = null,
        hardwareIssuer = null,
        hardwareNote = null,
        hardwarePeriod = null,
        hardwareAlgorithm = null,
        hardwareDigits = null
    )
    is EntryCredentialBackend.YubiKeyOath -> {
        val metadata = HardwareEntryMetadata(
            name = backend.name,
            issuer = backend.issuer,
            note = backend.note
        )
        val serializedMetadata = json.encodeToString(HardwareEntryMetadata.serializer(), metadata).encodeToByteArray()
        val encryptedContent = encryptionContext.encrypt(serializedMetadata, EncryptionTag.EntryContent)
        EntryEntity(
            id = id,
            content = encryptedContent,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            isDeleted = isDeleted,
            isSynced = isSynced,
            position = position,
            credentialBackendType = BACKEND_YUBIKEY_OATH,
            hardwareCredentialId = backend.credentialId,
            hardwareDeviceId = backend.deviceId,
            hardwareName = null,
            hardwareIssuer = null,
            hardwareNote = null,
            hardwarePeriod = backend.period,
            hardwareAlgorithm = backend.algorithm.value,
            hardwareDigits = backend.digits
        )
    }
}

private fun Entry.toDeleteEntity(): EntryEntity = EntryEntity(
    id = id,
    content = content,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    isDeleted = isDeleted,
    isSynced = isSynced,
    position = position,
    credentialBackendType = when (credentialBackend) {
        EntryCredentialBackend.LocalEncrypted -> BACKEND_LOCAL_ENCRYPTED
        is EntryCredentialBackend.YubiKeyOath -> BACKEND_YUBIKEY_OATH
    },
    hardwareCredentialId = (credentialBackend as? EntryCredentialBackend.YubiKeyOath)?.credentialId,
    hardwareDeviceId = (credentialBackend as? EntryCredentialBackend.YubiKeyOath)?.deviceId,
    hardwareName = null,
    hardwareIssuer = null,
    hardwareNote = null,
    hardwarePeriod = (credentialBackend as? EntryCredentialBackend.YubiKeyOath)?.period,
    hardwareAlgorithm = (credentialBackend as? EntryCredentialBackend.YubiKeyOath)?.algorithm?.value,
    hardwareDigits = (credentialBackend as? EntryCredentialBackend.YubiKeyOath)?.digits
)

private fun EntryEntity.toDomain(encryptionContext: EncryptionContext): Entry = Entry(
    id = id,
    content = content,
    createdAt = createdAt ?: modifiedAt,
    modifiedAt = modifiedAt,
    isDeleted = isDeleted,
    isSynced = isSynced,
    position = position,
    credentialBackend = toCredentialBackend(encryptionContext)
)

private fun EntryEntity.toCredentialBackend(encryptionContext: EncryptionContext): EntryCredentialBackend =
    when (credentialBackendType) {
        BACKEND_LOCAL_ENCRYPTED -> EntryCredentialBackend.LocalEncrypted
        BACKEND_YUBIKEY_OATH -> {
            val credId = hardwareCredentialId
            if (credId == null) {
                AuthenticatorLogger.w(TAG, "Missing YubiKey OATH credential id for entry $id, falling back to LocalEncrypted")
                EntryCredentialBackend.LocalEncrypted
            } else {
                val (name, issuer, note) = resolveHardwareMetadata(encryptionContext)
                EntryCredentialBackend.YubiKeyOath(
                    credentialId = credId,
                    deviceId = hardwareDeviceId,
                    name = name,
                    issuer = issuer,
                    note = note,
                    period = hardwarePeriod ?: DEFAULT_TOTP_PERIOD,
                    algorithm = hardwareAlgorithm?.let { algVal ->
                        runCatching { EntryAlgorithm.from(algVal) }
                            .onFailure {
                                AuthenticatorLogger.w(TAG, it, "Unknown algorithm $algVal for entry $id, falling back to SHA1")
                            }
                            .getOrDefault(EntryAlgorithm.SHA1)
                    } ?: EntryAlgorithm.SHA1,
                    digits = hardwareDigits ?: DEFAULT_TOTP_DIGITS
                )
            }
        }
        else -> {
            AuthenticatorLogger.w(TAG, "Unknown credential backend type '$credentialBackendType' for entry $id, falling back to LocalEncrypted")
            EntryCredentialBackend.LocalEncrypted
        }
    }

private fun EntryEntity.resolveHardwareMetadata(encryptionContext: EncryptionContext): Triple<String, String, String?> {
    if (content.array.isNotEmpty()) {
        val decrypted = runCatching {
            encryptionContext.decrypt(content, EncryptionTag.EntryContent)
        }.getOrNull()

        if (decrypted != null && decrypted.isNotEmpty()) {
            val metadata = runCatching {
                json.decodeFromString(HardwareEntryMetadata.serializer(), decrypted.decodeToString())
            }.getOrNull()

            if (metadata != null) {
                return Triple(metadata.name, metadata.issuer, metadata.note)
            }
        }
    }
    return Triple(hardwareName.orEmpty(), hardwareIssuer.orEmpty(), hardwareNote)
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

private const val TAG = "RoomEntriesPersistenceDataSource"
private const val BACKEND_LOCAL_ENCRYPTED = "local_encrypted"
private const val BACKEND_YUBIKEY_OATH = "yubikey_oath"
private const val DEFAULT_TOTP_PERIOD = 30
private const val DEFAULT_TOTP_DIGITS = 6

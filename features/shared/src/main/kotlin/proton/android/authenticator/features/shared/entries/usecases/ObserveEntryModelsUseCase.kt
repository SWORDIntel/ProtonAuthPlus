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

package proton.android.authenticator.features.shared.entries.usecases

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import proton.android.authenticator.business.entries.application.findall.FindAllEntriesQuery
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.entries.domain.EntryType
import proton.android.authenticator.commonrust.AuthenticatorIssuerMapperInterface
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.features.shared.entries.presentation.EntryModel
import proton.android.authenticator.shared.common.domain.infrastructure.queries.QueryBus
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.tags.EncryptionTag
import javax.inject.Inject

class ObserveEntryModelsUseCase @Inject constructor(
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val authenticatorIssuerMapper: AuthenticatorIssuerMapperInterface,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val queryBus: QueryBus
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(includeDeletedEntries: Boolean = false): Flow<List<EntryModel>> =
        FindAllEntriesQuery(includeDeleted = includeDeletedEntries)
            .let { query -> queryBus.ask<List<Entry>>(query) }
            .distinctUntilChanged()
            .mapLatest { entries ->
                encryptionContextProvider.withEncryptionContext {
                    entries.map { entry -> entryToModel(entry) }
                }.sortedWith(compareBy(EntryModel::position).thenByDescending(EntryModel::modifiedAt))
            }

    private fun EncryptionContext.entryToModel(entry: Entry): EntryModel = when (val backend = entry.credentialBackend) {
        EntryCredentialBackend.LocalEncrypted -> authenticatorClient.deserializeEntry(
            entry = decrypt(entry.content, EncryptionTag.EntryContent)
        ).let { authenticatorEntryModel ->
            authenticatorEntryModel to authenticatorClient.getTotpParams(
                entry = authenticatorEntryModel
            )
        }.let { (authenticatorEntryModel, authenticatorTotpParams) ->
            EntryModel(
                id = entry.id,
                position = entry.position,
                createdAt = entry.createdAt,
                modifiedAt = entry.modifiedAt,
                isDeleted = entry.isDeleted,
                isSynced = entry.isSynced,
                name = authenticatorEntryModel.name,
                issuer = authenticatorEntryModel.issuer,
                note = authenticatorEntryModel.note,
                secret = authenticatorEntryModel.secret,
                uri = authenticatorEntryModel.uri,
                period = authenticatorEntryModel.period.toInt(),
                type = EntryType.from(authenticatorEntryModel.entryType.ordinal),
                algorithm = EntryAlgorithm.from(authenticatorTotpParams.algorithm.ordinal),
                digits = authenticatorTotpParams.digits.toInt(),
                iconUrl = authenticatorIssuerMapper.lookup(authenticatorEntryModel.issuer)?.iconUrl,
                credentialBackend = backend
            )
        }

        is EntryCredentialBackend.YubiKeyOath -> EntryModel(
            id = entry.id,
            position = entry.position,
            createdAt = entry.createdAt,
            modifiedAt = entry.modifiedAt,
            isDeleted = entry.isDeleted,
            isSynced = entry.isSynced,
            name = backend.name,
            issuer = backend.issuer,
            note = backend.note,
            secret = "",
            uri = backend.codeUri,
            period = backend.period,
            type = EntryType.TOTP,
            algorithm = backend.algorithm,
            digits = backend.digits,
            iconUrl = authenticatorIssuerMapper.lookup(backend.issuer)?.iconUrl,
            credentialBackend = backend
        )
    }

}

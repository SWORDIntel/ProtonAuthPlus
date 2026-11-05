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

import kotlinx.coroutines.flow.first
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

internal class EntryDeleter @Inject constructor(
    private val repository: EntriesRepository,
    private val timeProvider: TimeProvider,
    private val yubiKeyOathCredentialDeleter: YubiKeyOathCredentialDeleter
) {

    internal suspend fun delete(id: String): Entry {
        val entry = repository.find(id).first()
        val backend = entry.credentialBackend
        if (backend is EntryCredentialBackend.YubiKeyOath) {
            if (yubiKeyOathCredentialDeleter.hasConnectedYubiKey()) {
                try {
                    yubiKeyOathCredentialDeleter.delete(backend.credentialId)
                } catch (e: Exception) {
                    AuthenticatorLogger.w(TAG, "Failed to delete physical credential for entry $id: ${e.message}")
                    AuthenticatorLogger.w(TAG, e)
                }
            } else {
                AuthenticatorLogger.d(TAG, "YubiKey not connected; skipping physical credential deletion for entry $id")
            }
        }
        return if (entry.isSynced) {
            val updatedEntry = entry.copy(
                isDeleted = true,
                modifiedAt = timeProvider.currentSeconds()
            )
            repository.save(updatedEntry)
            updatedEntry
        } else {
            repository.remove(entry)
            entry
        }
    }

    private companion object {
        private const val TAG = "EntryDeleter"
    }

}

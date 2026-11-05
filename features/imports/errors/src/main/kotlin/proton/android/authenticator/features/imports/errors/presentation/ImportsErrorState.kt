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

package proton.android.authenticator.features.imports.errors.presentation

import androidx.compose.runtime.Stable
import proton.android.authenticator.business.entries.application.importall.ImportEntriesReason
import proton.android.authenticator.features.imports.errors.R
import proton.android.authenticator.shared.ui.domain.models.UiText

@Stable
internal data class ImportsErrorState(private val errorReason: ImportEntriesReason) {

    internal val errorText: UiText = when (errorReason) {
        ImportEntriesReason.BadContent -> UiText.Resource(R.string.imports_error_dialog_message_bad_content)
        ImportEntriesReason.DecryptionFailed -> UiText.Resource(R.string.imports_error_dialog_message_bad_encryption)
        ImportEntriesReason.FileTooLarge -> UiText.Resource(R.string.imports_error_dialog_message_file_too_large)
        ImportEntriesReason.BadPassword -> UiText.Resource(R.string.imports_error_dialog_message_bad_password)
        ImportEntriesReason.MissingPassword -> UiText.Resource(R.string.imports_error_dialog_message_missing_password)
        ImportEntriesReason.YubiKeyTimeout -> UiText.Dynamic("YubiKey discovery timed out. Please insert or tap your YubiKey.")
        ImportEntriesReason.YubiKeyLocked -> UiText.Dynamic("YubiKey OATH session is password-protected or locked.")
        ImportEntriesReason.YubiKeyStorageFull -> UiText.Dynamic("YubiKey OATH storage is full. Please remove unused credentials.")
        ImportEntriesReason.YubiKeyError -> UiText.Dynamic("Failed to write credentials to YubiKey hardware.")
    }

}

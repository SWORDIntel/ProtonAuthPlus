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

package proton.android.authenticator.features.backups.errors.presentation

import androidx.compose.runtime.Immutable
import proton.android.authenticator.business.backups.application.generate.GenerateBackupReason
import proton.android.authenticator.features.backups.errors.R
import proton.android.authenticator.shared.ui.domain.models.UiText

@Immutable
internal data class BackupsErrorsState(private val errorReason: GenerateBackupReason) {

    internal val messageTexts: List<UiText> = buildList {
        val (errorResId, suggestionResId) = when (errorReason) {
            GenerateBackupReason.PasswordRequired ->
                R.string.backup_error_dialog_message_error_password to R.string.backup_error_dialog_message_error_password_suggestion

            GenerateBackupReason.NoEntries ->
                R.string.backup_error_dialog_message_error_no_entries to R.string.backup_error_dialog_message_error_no_entries_suggestion

            GenerateBackupReason.FileCreationFailed ->
                R.string.backup_error_dialog_message_error_file_permission to R.string.backup_error_dialog_message_error_file_permission_suggestion

            GenerateBackupReason.HardwareBackedEntries ->
                R.string.backup_error_dialog_message_error_hardware_backed to R.string.backup_error_dialog_message_error_suggestion

            GenerateBackupReason.MissingFileName ->
                R.string.backup_error_dialog_message_error_folder to R.string.backup_error_dialog_message_error_suggestion

            GenerateBackupReason.NotEnabled ->
                R.string.backup_error_dialog_message_error_not_enabled to R.string.backup_error_dialog_message_error_suggestion

            GenerateBackupReason.CannotGenerate ->
                R.string.backup_error_dialog_message_error_unknown to R.string.backup_error_dialog_message_error_suggestion
        }

        add(UiText.Resource(id = errorResId))
        add(UiText.Resource(id = suggestionResId))
    }

}

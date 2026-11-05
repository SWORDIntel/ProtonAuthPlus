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

package proton.android.authenticator.features.imports.scan.presentation

import androidx.compose.runtime.Stable
import proton.android.authenticator.business.entries.domain.EntryImportType
import proton.android.authenticator.shared.common.domain.models.MimeType

@Stable
internal data class ImportsScanState(
    internal val hasCameraPermission: Boolean?,
    internal val event: ImportsScanEvent,
    internal val importType: EntryImportType,
    internal val isWaitingForYubiKey: Boolean = false,
    internal val yubiKeyPromptError: String? = null
) {

    internal val isMultiSelectionAllowed: Boolean = when (importType) {
        EntryImportType.Google -> true
        EntryImportType.Aegis,
        EntryImportType.Authy,
        EntryImportType.Bitwarden,
        EntryImportType.Ente,
        EntryImportType.LastPass,
        EntryImportType.Microsoft,
        EntryImportType.ProtonAuthenticator,
        EntryImportType.ProtonPass,
        EntryImportType.TwoFas -> false
    }

    internal val mimeTypes: List<String> = importType.mimeTypes.map(MimeType::value)

    internal val showBottomBar: Boolean = hasCameraPermission == true

}

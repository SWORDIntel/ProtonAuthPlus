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

package proton.android.authenticator.features.home.delete.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import proton.android.authenticator.business.entries.application.delete.DeleteEntryReason
import proton.android.authenticator.features.home.delete.R
import proton.android.authenticator.features.home.delete.presentation.HomeDeleteEvent
import proton.android.authenticator.features.home.delete.presentation.HomeDeleteViewModel
import proton.android.authenticator.shared.ui.domain.models.UiText
import proton.android.authenticator.shared.ui.domain.screens.AlertDialogScreen
import proton.android.authenticator.shared.ui.R as uiR

@Composable
fun HomeDeleteScreen(
    onDismissed: () -> Unit,
    onError: (Int) -> Unit,
    onSuccess: () -> Unit
) = with(hiltViewModel<HomeDeleteViewModel>()) {
    val state by stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(key1 = state.event) {
        when (val event = state.event) {
            HomeDeleteEvent.Idle -> Unit

            is HomeDeleteEvent.OnDeleteEntryError -> {
                val messageRes = when (DeleteEntryReason.entries[event.errorReason]) {
                    DeleteEntryReason.EntryNotFound -> R.string.home_delete_entry_not_found_error
                    DeleteEntryReason.YubiKeyUnavailable -> R.string.home_delete_yubikey_unavailable_error
                    DeleteEntryReason.YubiKeyDeleteFailed -> R.string.home_delete_yubikey_delete_failed_error
                }
                onError(messageRes)
            }

            HomeDeleteEvent.OnDeleteEntrySuccess -> {
                onSuccess()
            }
        }

        onConsumeEvent(event = state.event)
    }

    AlertDialogScreen(
        title = UiText.Resource(id = R.string.home_delete_error_dialog_title),
        message = UiText.Resource(id = R.string.home_delete_error_dialog_message),
        onDismissRequest = onDismissed,
        confirmText = UiText.Resource(id = uiR.string.action_delete),
        onConfirmation = ::onDeleteEntry,
        cancelText = UiText.Resource(id = uiR.string.action_cancel),
        onCancellation = onDismissed
    )
}

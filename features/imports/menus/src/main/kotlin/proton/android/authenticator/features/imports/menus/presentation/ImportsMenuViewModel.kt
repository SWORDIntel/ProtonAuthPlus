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

package proton.android.authenticator.features.imports.menus.presentation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import proton.android.authenticator.business.entries.application.importall.ImportEntriesReason
import proton.android.authenticator.business.entries.domain.EntryImportType
import proton.android.authenticator.features.imports.shared.usecases.ImportEntriesUseCase
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

@[HiltViewModel OptIn(ExperimentalCoroutinesApi::class)]
internal class ImportsMenuViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importEntriesUseCase: ImportEntriesUseCase
) : ViewModel() {

    private val importType = requireNotNull<Int>(savedStateHandle[ARGS_IMPORT_TYPE])
        .let(enumValues<EntryImportType>()::get)

    private val eventFlow = MutableStateFlow<ImportsMenuEvent>(value = ImportsMenuEvent.Idle)

    internal val stateFlow: StateFlow<ImportsMenuState> = eventFlow
        .mapLatest { event ->
            ImportsMenuState(
                importType = importType,
                event = event
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = ImportsMenuState(
                importType = importType,
                event = ImportsMenuEvent.Idle
            )
        )

    internal fun onConsumeEvent(event: ImportsMenuEvent) {
        eventFlow.compareAndSet(expect = event, update = ImportsMenuEvent.Idle)
    }

    internal fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            AuthenticatorLogger.i(TAG, "Import canceled: no files selected")

            return
        }

        viewModelScope.launch {
            importEntriesUseCase(uris, importType).fold(
                onFailure = { reason ->
                    AuthenticatorLogger.w(TAG, "Failed to import entries for $importType: $reason")

                    when (reason) {
                        ImportEntriesReason.BadContent,
                        ImportEntriesReason.BadPassword,
                        ImportEntriesReason.DecryptionFailed,
                        ImportEntriesReason.FileTooLarge,
                        ImportEntriesReason.YubiKeyTimeout,
                        ImportEntriesReason.YubiKeyLocked,
                        ImportEntriesReason.YubiKeyStorageFull,
                        ImportEntriesReason.YubiKeyError -> {
                            ImportsMenuEvent.OnImportFailed(reason = reason.ordinal)
                        }

                        ImportEntriesReason.MissingPassword -> {
                            ImportsMenuEvent.OnImportPasswordRequired(
                                uri = uris.first().toString(),
                                importType = importType.ordinal
                            )
                        }
                    }
                },
                onSuccess = { importedEntriesCount ->
                    AuthenticatorLogger.i(TAG, "Entries import succeeded for $importType")

                    ImportsMenuEvent.OnImportSucceeded(importedEntriesCount = importedEntriesCount)
                }
            ).also { event -> eventFlow.update { event } }
        }
    }

    internal fun onOptionSelected(option: ImportsMenuOption) {
        when (option.optionType) {
            ImportsMenuOptionType.ScanQrCode -> {
                ImportsMenuEvent.OnScanQrCode(importType = importType.ordinal)
            }

            ImportsMenuOptionType.SelectFromGallery -> {
                ImportsMenuEvent.OnSelectFromGallery
            }
        }.also { event -> eventFlow.update { event } }
    }

    private companion object {

        private const val TAG = "ImportsMenuViewModel"

        private const val ARGS_IMPORT_TYPE = "importType"

    }

}

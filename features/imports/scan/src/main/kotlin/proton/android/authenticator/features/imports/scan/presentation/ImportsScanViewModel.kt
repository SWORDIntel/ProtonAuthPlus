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

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import proton.android.authenticator.business.entries.application.importall.ImportEntriesReason
import proton.android.authenticator.business.entries.domain.EntryImportType
import proton.android.authenticator.features.imports.shared.usecases.ImportEntriesUseCase
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

@HiltViewModel
class ImportsScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importEntriesUseCase: ImportEntriesUseCase
) : ViewModel() {

    private val importType = requireNotNull<Int>(savedStateHandle[ARGS_IMPORT_TYPE])
        .let(enumValues<EntryImportType>()::get)

    private val hasCameraPermissionFlow = MutableStateFlow<Boolean?>(value = null)

    private val eventFlow = MutableStateFlow<ImportsScanEvent>(value = ImportsScanEvent.Idle)

    private val isWaitingForYubiKeyFlow = MutableStateFlow(value = false)

    private val yubiKeyPromptErrorFlow = MutableStateFlow<String?>(value = null)

    private var pendingImportBytes: ByteArray? = null

    private var pendingImportUris: List<Uri>? = null

    private var importJob: kotlinx.coroutines.Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val stateFlow: StateFlow<ImportsScanState> = combine(
        hasCameraPermissionFlow,
        eventFlow,
        isWaitingForYubiKeyFlow,
        yubiKeyPromptErrorFlow
    ) { hasCameraPermission, event, isWaitingForYubiKey, yubiKeyPromptError ->
        ImportsScanState(
            hasCameraPermission = hasCameraPermission,
            importType = importType,
            event = event,
            isWaitingForYubiKey = isWaitingForYubiKey,
            yubiKeyPromptError = yubiKeyPromptError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ImportsScanState(
            hasCameraPermission = null,
            event = ImportsScanEvent.Idle,
            importType = importType,
            isWaitingForYubiKey = false,
            yubiKeyPromptError = null
        )
    )

    internal fun onConsumeEvent(event: ImportsScanEvent) {
        eventFlow.compareAndSet(expect = event, update = ImportsScanEvent.Idle)
    }

    internal fun onCameraPermissionRequested(isGranted: Boolean) {
        hasCameraPermissionFlow.update { isGranted }
    }

    internal fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            AuthenticatorLogger.i(TAG, "Import canceled: no files selected")

            return
        }

        pendingImportBytes = null
        pendingImportUris = uris
        startImportWithYubiKeyPrompt()
    }

    internal fun onQrCodeScanned(qrCodeBytes: ByteArray) {
        pendingImportBytes = qrCodeBytes
        pendingImportUris = null
        startImportWithYubiKeyPrompt()
    }

    internal fun onRetryYubiKeyImport() {
        startImportWithYubiKeyPrompt()
    }

    internal fun onCancelYubiKeyImport() {
        importJob?.cancel()
        importJob = null
        isWaitingForYubiKeyFlow.value = false
        yubiKeyPromptErrorFlow.value = null
        pendingImportBytes = null
        pendingImportUris = null
    }

    private fun startImportWithYubiKeyPrompt() {
        isWaitingForYubiKeyFlow.value = true
        yubiKeyPromptErrorFlow.value = null

        importJob?.cancel()
        importJob = viewModelScope.launch {
            val bytes = pendingImportBytes
            val uris = pendingImportUris

            val result = if (bytes != null) {
                importEntriesUseCase(bytes = bytes, importType = importType)
            } else if (!uris.isNullOrEmpty()) {
                importEntriesUseCase(uris = uris, importType = importType)
            } else {
                isWaitingForYubiKeyFlow.value = false
                return@launch
            }

            result.fold(
                onFailure = { reason ->
                    AuthenticatorLogger.w(TAG, "Failed to import entries for $importType: $reason")

                    when (reason) {
                        ImportEntriesReason.YubiKeyTimeout -> {
                            yubiKeyPromptErrorFlow.value = "YubiKey not detected. Please insert your YubiKey into your phone and tap Retry."
                        }

                        ImportEntriesReason.YubiKeyLocked -> {
                            yubiKeyPromptErrorFlow.value = "YubiKey OATH is locked. Please unlock your key and tap Retry."
                        }

                        ImportEntriesReason.YubiKeyStorageFull -> {
                            yubiKeyPromptErrorFlow.value = "YubiKey storage is full (32 slots max). Please free slots on your key and tap Retry."
                        }

                        ImportEntriesReason.YubiKeyError -> {
                            yubiKeyPromptErrorFlow.value = "Error communicating with YubiKey. Please reconnect your key and tap Retry."
                        }

                        ImportEntriesReason.MissingPassword -> {
                            isWaitingForYubiKeyFlow.value = false
                            val uri = uris?.firstOrNull()?.toString().orEmpty()
                            ImportsScanEvent.OnImportPasswordRequired(
                                uri = uri,
                                importType = importType.ordinal
                            ).also { event -> eventFlow.update { event } }
                        }

                        ImportEntriesReason.BadContent,
                        ImportEntriesReason.BadPassword,
                        ImportEntriesReason.DecryptionFailed,
                        ImportEntriesReason.FileTooLarge -> {
                            isWaitingForYubiKeyFlow.value = false
                            ImportsScanEvent.OnImportFailed(reason = reason.ordinal).also { event ->
                                eventFlow.update { event }
                            }
                        }
                    }
                },
                onSuccess = { importedEntriesCount ->
                    AuthenticatorLogger.i(TAG, "Entries import succeeded for $importType: $importedEntriesCount entries written to YubiKey")
                    isWaitingForYubiKeyFlow.value = false
                    yubiKeyPromptErrorFlow.value = null
                    pendingImportBytes = null
                    pendingImportUris = null
                    ImportsScanEvent.OnImportSucceeded(importedEntriesCount = importedEntriesCount).also { event ->
                        eventFlow.update { event }
                    }
                }
            )
        }
    }

    private companion object {

        private const val TAG = "ImportsScanViewModel"

        private const val ARGS_IMPORT_TYPE = "importType"

    }

}

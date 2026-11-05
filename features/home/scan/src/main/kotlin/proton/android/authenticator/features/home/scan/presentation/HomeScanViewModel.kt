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

package proton.android.authenticator.features.home.scan.presentation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import proton.android.authenticator.business.entries.application.create.CreateEntryReason
import proton.android.authenticator.features.home.scan.R
import proton.android.authenticator.features.home.scan.usecases.CreateEntryUseCase
import proton.android.authenticator.features.home.scan.usecases.ScanEntryQrUseCase
import proton.android.authenticator.features.shared.usecases.snackbars.DispatchSnackbarEventUseCase
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.models.SnackbarEvent
import javax.inject.Inject

@HiltViewModel
internal class HomeScanViewModel @Inject constructor(
    private val createEntryUseCase: CreateEntryUseCase,
    private val scanEntryQrUseCase: ScanEntryQrUseCase,
    private val dispatchSnackbarEventUseCase: DispatchSnackbarEventUseCase
) : ViewModel() {

    private val hasCameraPermissionFlow = MutableStateFlow<Boolean?>(value = null)

    private val eventFlow = MutableStateFlow<HomeScanEvent>(value = HomeScanEvent.Idle)

    private val isYubiKeyBackedFlow = MutableStateFlow(value = false)

    internal val stateFlow: StateFlow<HomeScanState> = combine(
        hasCameraPermissionFlow,
        eventFlow,
        isYubiKeyBackedFlow,
        ::HomeScanState
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeScanState.Initial
    )

    internal fun onConsumeEvent(event: HomeScanEvent) {
        eventFlow.compareAndSet(expect = event, update = HomeScanEvent.Idle)
    }

    internal fun onCameraPermissionRequested(isGranted: Boolean) {
        hasCameraPermissionFlow.update { isGranted }
    }

    internal fun onYubiKeyBackedClick() {
        isYubiKeyBackedFlow.update { isEnabled -> !isEnabled }
    }

    internal fun onCreateEntry(uri: String) {
        viewModelScope.launch {
            when (val answer = createEntryUseCase(uri = uri, isYubiKeyBacked = isYubiKeyBackedFlow.value)) {
                is Answer.Success -> {
                    eventFlow.update { HomeScanEvent.OnEntryCreationSucceeded }
                }

                is Answer.Failure -> {
                    when (answer.reason) {
                        CreateEntryReason.YubiKeyTimeout -> {
                            dispatchSnackbarEvent(R.string.home_scan_yubikey_timeout_error)
                        }

                        CreateEntryReason.YubiKeyLocked -> {
                            dispatchSnackbarEvent(R.string.home_scan_yubikey_locked_error)
                        }

                        CreateEntryReason.YubiKeyStorageFull -> {
                            dispatchSnackbarEvent(R.string.home_scan_yubikey_storage_full_error)
                        }

                        CreateEntryReason.YubiKeyError -> {
                            dispatchSnackbarEvent(R.string.home_scan_yubikey_generic_error)
                        }

                        else -> {
                            eventFlow.update { HomeScanEvent.OnEntryCreationFailed }
                        }
                    }
                }
            }
        }
    }

    private fun dispatchSnackbarEvent(@StringRes messageResId: Int) {
        viewModelScope.launch {
            dispatchSnackbarEventUseCase(snackbarEvent = SnackbarEvent(messageResId = messageResId))
        }
    }

    internal fun onScanEntryQr(uri: Uri?) {
        if (uri == null) return

        viewModelScope.launch {
            scanEntryQrUseCase(uri = uri).also { entryUriCandidate ->
                entryUriCandidate
                    ?.also(::onCreateEntry)
                    ?: eventFlow.update { HomeScanEvent.OnEntryCreationFailed }
            }
        }
    }

}

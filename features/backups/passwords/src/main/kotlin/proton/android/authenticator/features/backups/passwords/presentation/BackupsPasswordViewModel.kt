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

package proton.android.authenticator.features.backups.passwords.presentation

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.crypto.common.keystore.EncryptedString
import proton.android.authenticator.features.shared.usecases.backups.ObserveBackupUseCase
import proton.android.authenticator.features.shared.usecases.backups.UpdateBackupUseCase
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import javax.inject.Inject

@HiltViewModel
internal class BackupsPasswordViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val observeBackupUseCase: ObserveBackupUseCase,
    private val updateBackupUseCase: UpdateBackupUseCase
) : ViewModel() {

    private val backupUri = requireNotNull<String>(savedStateHandle[ARGS_URI])
        .let(Uri::parse)

    private val passwordState = mutableStateOf<String?>(value = null)
    private val checkPasswordState = mutableStateOf<String?>(value = null)


    private val isPasswordVisibleFlow = MutableStateFlow(value = false)
    private val isCheckPasswordVisibleFlow = MutableStateFlow(value = false)
    private val eventFlow = MutableStateFlow<BackupsPasswordEvent>(
        value = BackupsPasswordEvent.Idle
    )

    internal val stateFlow: StateFlow<BackupsPasswordState> = combine(
        snapshotFlow { passwordState.value.orEmpty() },
        snapshotFlow { checkPasswordState.value.orEmpty() },
        isPasswordVisibleFlow,
        isCheckPasswordVisibleFlow,
        eventFlow,
        ::BackupsPasswordState
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = BackupsPasswordState.Initial
    )

    internal fun onConsumeEvent(event: BackupsPasswordEvent) {
        eventFlow.compareAndSet(expect = event, update = BackupsPasswordEvent.Idle)
    }

    internal fun onPasswordChange(newPassword: String) {
        passwordState.value = newPassword
    }

    internal fun onCheckPasswordChange(newPassword: String) {
        checkPasswordState.value = newPassword
    }

    internal fun onPasswordVisibilityChange(newIsVisible: Boolean) {
        isPasswordVisibleFlow.update { newIsVisible }
    }

    internal fun onCheckPasswordVisibilityChange(newIsVisible: Boolean) {
        isCheckPasswordVisibleFlow.update { newIsVisible }
    }

    internal fun onEnableBackupWithPassword() {
        if (passwordState.value != checkPasswordState.value) {
            AuthenticatorLogger.w(
                TAG,
                "Backup enable failed: Password and check password do not match"
            )
            return
        }

        passwordState.value?.let { password ->
            if (password.isBlank()) {
                AuthenticatorLogger.w(TAG, "Backup enable failed: Password cannot be blank")
                return@let
            }

            viewModelScope.launch {
                encryptionContextProvider.withEncryptionContext {
                    encrypt(password)
                }.also(::enableBackup)
            }
        }
    }

    private fun enableBackup(encryptedPassword: EncryptedString) {
        viewModelScope.launch {
            observeBackupUseCase().first()
                .copy(
                    isEnabled = true,
                    directoryUri = backupUri,
                    encryptedPassword = encryptedPassword
                )
                .let { backup ->
                    updateBackupUseCase(newBackup = backup)
                }
                .fold(
                    onFailure = { reason ->
                        AuthenticatorLogger.w(TAG, "Backup enable failed due to: $reason")

                        BackupsPasswordEvent.OnBackupEnableError(errorReason = reason.ordinal)
                    },
                    onSuccess = {
                        AuthenticatorLogger.i(TAG, "Backup successfully enabled")

                        BackupsPasswordEvent.OnBackupEnableSuccess
                    }
                )
                .also {
                    passwordState.value = null
                }
                .also { event ->
                    eventFlow.update { event }
                }
        }
    }

    private companion object {

        private const val TAG = "BackupsPasswordViewModel"

        private const val ARGS_URI = "uri"

    }

}

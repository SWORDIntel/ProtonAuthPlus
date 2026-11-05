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

package proton.android.authenticator.features.imports.scan.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import proton.android.authenticator.features.imports.scan.presentation.ImportsScanEvent
import proton.android.authenticator.features.imports.scan.presentation.ImportsScanViewModel
import proton.android.authenticator.shared.common.domain.models.MimeType
import proton.android.authenticator.shared.ui.domain.models.UiText
import proton.android.authenticator.shared.ui.domain.screens.AlertDialogScreen
import proton.android.authenticator.shared.ui.domain.screens.ScaffoldScreen
import proton.android.authenticator.shared.ui.domain.theme.ThemePadding

@Composable
fun ImportsScanScreen(
    onCloseClick: () -> Unit,
    onCompleted: (Int) -> Unit,
    onFailed: (Int) -> Unit,
    onPasswordRequired: (String, Int) -> Unit,
    onPermissionRequired: (Int) -> Unit
) = with(hiltViewModel<ImportsScanViewModel>()) {
    val state by stateFlow.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            buildList<Uri> {
                result.data?.data?.also(::add)

                result.data?.clipData?.let { clipData ->
                    for (index in 0 until clipData.itemCount) {
                        clipData.getItemAt(index).uri.also(::add)
                    }
                }
            }.also(::onFilesPicked)
        }
    )

    LaunchedEffect(key1 = state.event) {
        when (val event = state.event) {
            ImportsScanEvent.Idle -> Unit

            is ImportsScanEvent.OnImportFailed -> {
                onFailed(event.reason)
            }

            is ImportsScanEvent.OnImportPasswordRequired -> {
                onPasswordRequired(event.uri, event.importType)
            }

            is ImportsScanEvent.OnImportSucceeded -> {
                onCompleted(event.importedEntriesCount)
            }
        }

        onConsumeEvent(event = state.event)
    }

    if (state.isWaitingForYubiKey) {
        val promptError = state.yubiKeyPromptError
        AlertDialogScreen(
            title = UiText.Dynamic("Insert YubiKey to Finish Import"),
            messages = listOf(
                if (promptError != null) {
                    UiText.Dynamic(promptError)
                } else {
                    UiText.Dynamic("Proton Authenticator + enforces hardware security. Please insert or tap your YubiKey to finish the import.")
                }
            ),
            confirmText = UiText.Dynamic("Retry"),
            onConfirmation = ::onRetryYubiKeyImport,
            cancelText = UiText.Dynamic("Cancel"),
            onCancellation = ::onCancelYubiKeyImport,
            onDismissRequest = ::onCancelYubiKeyImport,
            isLoading = promptError == null
        )
    }

    ScaffoldScreen(
        bottomBar = {
            if (state.showBottomBar) {
                ImportsScanBottomBar(
                    modifier = Modifier
                        .imePadding()
                        .systemBarsPadding()
                        .fillMaxWidth()
                        .padding(
                            start = ThemePadding.Medium,
                            end = ThemePadding.Medium,
                            bottom = ThemePadding.Large
                        ),
                    onCloseClick = onCloseClick,
                    onOpenGalleryClick = {
                        Intent(Intent.ACTION_GET_CONTENT)
                            .apply {
                                type = MimeType.All.value
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, state.isMultiSelectionAllowed)
                                putExtra(Intent.EXTRA_MIME_TYPES, state.mimeTypes.toTypedArray())
                            }
                            .also(launcher::launch)
                    }
                )
            }
        }
    ) {
        ImportsScanContent(
            modifier = Modifier.fillMaxSize(),
            state = state,
            onCameraError = onCloseClick,
            onQrCodeScanned = ::onQrCodeScanned,
            onPermissionRequested = ::onCameraPermissionRequested,
            onPermissionRequired = onPermissionRequired
        )
    }
}

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

package proton.android.authenticator.features.home.manual.presentation

import androidx.compose.runtime.Immutable
import proton.android.authenticator.features.home.manual.R
import proton.android.authenticator.shared.ui.domain.models.UiText

@Immutable
internal sealed interface HomeManualState {

    val event: HomeManualEvent

    @Immutable
    data object Loading : HomeManualState {

        override val event: HomeManualEvent = HomeManualEvent.Idle

    }

    @Immutable
    data class Ready(
        override val event: HomeManualEvent,
        internal val formModel: HomeManualFormModel,
        internal val showMigrateConfirmationDialog: Boolean = false
    ) : HomeManualState {

        internal val title: UiText = when (formModel.mode) {
            HomeManualMode.Create -> R.string.home_manual_screen_title_create
            HomeManualMode.Edit -> R.string.home_manual_screen_title_edit
        }.let(UiText::Resource)

    }

}

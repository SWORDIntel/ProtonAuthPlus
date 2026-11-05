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

package proton.android.authenticator.features.home.master.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.entries.domain.EntryType
import proton.android.authenticator.business.entrycodes.domain.EntryCode
import proton.android.authenticator.features.shared.entries.presentation.EntryModel

internal class HomeMasterEntryModelTest {

    private val entryCode = EntryCode(
        currentCode = "123456",
        nextCode = "654321"
    )

    @Test
    fun `isHardwareBacked is true when credentialBackend is YubiKeyOath`() {
        val entryModel = createEntryModel(
            credentialBackend = EntryCredentialBackend.YubiKeyOath(
                credentialId = "yubi-1",
                deviceId = "dev-1",
                name = "Test",
                issuer = "Issuer",
                note = null,
                period = 30,
                algorithm = EntryAlgorithm.SHA1,
                digits = 6
            )
        )

        val homeEntry = HomeMasterEntryModel(entryModel = entryModel, entryCode = entryCode)

        assertTrue(homeEntry.isHardwareBacked)
    }

    @Test
    fun `isHardwareBacked is false when credentialBackend is LocalEncrypted`() {
        val entryModel = createEntryModel(
            credentialBackend = EntryCredentialBackend.LocalEncrypted
        )

        val homeEntry = HomeMasterEntryModel(entryModel = entryModel, entryCode = entryCode)

        assertFalse(homeEntry.isHardwareBacked)
    }

    private companion object {

        fun createEntryModel(
            credentialBackend: EntryCredentialBackend
        ) = EntryModel(
            id = "1",
            name = "Test",
            issuer = "Issuer",
            note = null,
            period = 30,
            secret = "secret",
            type = EntryType.TOTP,
            uri = "otpauth://totp/Test?secret=secret",
            algorithm = EntryAlgorithm.SHA1,
            digits = 6,
            iconUrl = null,
            position = 0,
            createdAt = 0L,
            modifiedAt = 0L,
            isDeleted = false,
            isSynced = true,
            credentialBackend = credentialBackend
        )
    }
}

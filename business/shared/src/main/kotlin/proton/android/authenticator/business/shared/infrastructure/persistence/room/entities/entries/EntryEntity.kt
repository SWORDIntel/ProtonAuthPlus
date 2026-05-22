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

package proton.android.authenticator.business.shared.infrastructure.persistence.room.entities.entries

import androidx.room.ColumnInfo
import androidx.room.Entity
import me.proton.core.crypto.common.keystore.EncryptedByteArray

@Entity(
    tableName = EntryEntity.TABLE,
    primaryKeys = [EntryEntity.Columns.ID]
)
data class EntryEntity(
    @ColumnInfo(name = Columns.ID, index = true)
    val id: String,
    @ColumnInfo(name = Columns.ENCRYPTED_CONTENT)
    val content: EncryptedByteArray,
    @ColumnInfo(name = Columns.IS_DELETED, defaultValue = "0")
    val isDeleted: Boolean,
    @ColumnInfo(name = Columns.IS_SYNCED, defaultValue = "0")
    val isSynced: Boolean,
    @ColumnInfo(name = Columns.POSITION)
    val position: Int,
    @ColumnInfo(name = Columns.CREATED_AT)
    val createdAt: Long?,
    @ColumnInfo(name = Columns.MODIFIED_AT)
    val modifiedAt: Long,
    @ColumnInfo(name = Columns.CREDENTIAL_BACKEND_TYPE, defaultValue = "local_encrypted")
    val credentialBackendType: String,
    @ColumnInfo(name = Columns.HARDWARE_CREDENTIAL_ID)
    val hardwareCredentialId: String?,
    @ColumnInfo(name = Columns.HARDWARE_DEVICE_ID)
    val hardwareDeviceId: String?,
    @ColumnInfo(name = Columns.HARDWARE_NAME)
    val hardwareName: String?,
    @ColumnInfo(name = Columns.HARDWARE_ISSUER)
    val hardwareIssuer: String?,
    @ColumnInfo(name = Columns.HARDWARE_NOTE)
    val hardwareNote: String?,
    @ColumnInfo(name = Columns.HARDWARE_PERIOD)
    val hardwarePeriod: Int?,
    @ColumnInfo(name = Columns.HARDWARE_ALGORITHM)
    val hardwareAlgorithm: Int?,
    @ColumnInfo(name = Columns.HARDWARE_DIGITS)
    val hardwareDigits: Int?
) {

    internal object Columns {

        internal const val ID = "id"

        internal const val ENCRYPTED_CONTENT = "encrypted_content"

        internal const val IS_DELETED = "is_deleted"

        internal const val IS_SYNCED = "is_synced"

        internal const val POSITION = "position"

        internal const val CREATED_AT = "created_at"

        internal const val MODIFIED_AT = "modified_at"

        internal const val CREDENTIAL_BACKEND_TYPE = "credential_backend_type"

        internal const val HARDWARE_CREDENTIAL_ID = "hardware_credential_id"

        internal const val HARDWARE_DEVICE_ID = "hardware_device_id"

        internal const val HARDWARE_NAME = "hardware_name"

        internal const val HARDWARE_ISSUER = "hardware_issuer"

        internal const val HARDWARE_NOTE = "hardware_note"

        internal const val HARDWARE_PERIOD = "hardware_period"

        internal const val HARDWARE_ALGORITHM = "hardware_algorithm"

        internal const val HARDWARE_DIGITS = "hardware_digits"

    }

    internal companion object {

        internal const val TABLE = "EntryEntity"

    }

}

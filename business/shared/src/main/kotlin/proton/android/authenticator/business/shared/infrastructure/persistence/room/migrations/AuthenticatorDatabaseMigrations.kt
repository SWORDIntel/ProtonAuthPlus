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

package proton.android.authenticator.business.shared.infrastructure.persistence.room.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.featureflag.data.db.FeatureFlagDatabase

internal object AuthenticatorDatabaseMigrations {

    internal val Migration_3_4 = object : Migration(startVersion = 3, endVersion = 4) {

        override fun migrate(db: SupportSQLiteDatabase) {
            FeatureFlagDatabase.MIGRATION_4.migrate(db)
        }

    }

    internal val Migration_4_5 = object : Migration(startVersion = 4, endVersion = 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AccountDatabase.MIGRATION_11.migrate(db)
        }
    }

    internal val Migration_5_6 = object : Migration(startVersion = 5, endVersion = 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Update AccountMetadataEntity records from 'Pass' to 'Authenticator'
            db.execSQL("UPDATE AccountMetadataEntity SET product = 'Authenticator' WHERE product = 'Pass'")

            // Update SessionEntity records from 'Pass' to 'Authenticator'
            db.execSQL("UPDATE SessionEntity SET product = 'Authenticator' WHERE product = 'Pass'")
        }
    }

    internal val Migration_6_7 = object : Migration(startVersion = 6, endVersion = 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE EntryEntity ADD COLUMN credential_backend_type TEXT NOT NULL DEFAULT 'local_encrypted'"
            )
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_credential_id TEXT")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_device_id TEXT")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_name TEXT")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_issuer TEXT")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_note TEXT")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_period INTEGER")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_algorithm INTEGER")
            db.execSQL("ALTER TABLE EntryEntity ADD COLUMN hardware_digits INTEGER")
        }
    }
}

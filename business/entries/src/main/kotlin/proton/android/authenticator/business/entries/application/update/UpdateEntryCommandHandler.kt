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

package proton.android.authenticator.business.entries.application.update

import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.TimeoutException
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import kotlinx.coroutines.TimeoutCancellationException
import proton.android.authenticator.business.entries.application.create.YubiKeyOathCredentialNotFoundError
import proton.android.authenticator.business.entries.application.create.YubiKeyOathEnrollmentLockedError
import proton.android.authenticator.business.shared.domain.errors.ErrorLoggingUtils
import proton.android.authenticator.commonrust.AuthenticatorException
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.infrastructure.commands.CommandHandler
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import java.io.IOException
import javax.inject.Inject

internal class UpdateEntryCommandHandler @Inject constructor(
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val updater: EntryUpdater
) : CommandHandler<UpdateEntryCommand, Unit, UpdateEntryReason> {

    override suspend fun handle(command: UpdateEntryCommand): Answer<Unit, UpdateEntryReason> = try {
        when (command) {
            is UpdateEntryCommand.ToYubiKeyTotp -> updater.migrateToYubiKeyTotp(command)
            else -> {
                command.toModel(authenticatorClient)
                    .let { model ->
                        updater.update(
                            id = command.id,
                            position = command.position,
                            model = model
                        )
                    }
            }
        }
            .also {
                AuthenticatorLogger.i(TAG, "Successfully updated entry with id: ${command.id}")
            }
            .let(Answer<Unit, UpdateEntryReason>::Success)
    } catch (e: TimeoutCancellationException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to timeout",
            reason = UpdateEntryReason.YubiKeyTimeout,
            tag = TAG
        )
    } catch (e: TimeoutException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to YubiKey timeout",
            reason = UpdateEntryReason.YubiKeyTimeout,
            tag = TAG
        )
    } catch (e: YubiKeyOathEnrollmentLockedError) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not migrate entry because YubiKey OATH is locked",
            reason = UpdateEntryReason.YubiKeyLocked,
            tag = TAG
        )
    } catch (e: YubiKeyVerificationFailedException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not migrate entry because YubiKey verification failed",
            reason = UpdateEntryReason.VerificationFailed,
            tag = TAG
        )
    } catch (e: ApduException) {
        val reason = when (e.sw) {
            SW.NO_SPACE, SW.MEMORY_ERROR -> UpdateEntryReason.YubiKeyStorageFull
            SW.AUTH_METHOD_BLOCKED, SW.SECURITY_CONDITION_NOT_SATISFIED -> UpdateEntryReason.YubiKeyLocked
            else -> UpdateEntryReason.YubiKeyError
        }
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to APDU error (SW: ${e.sw})",
            reason = reason,
            tag = TAG
        )
    } catch (e: CommandException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to YubiKey command error",
            reason = UpdateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: IOException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to I/O error",
            reason = UpdateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: YubiKeyOathCredentialNotFoundError) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry: credential not found",
            reason = UpdateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: AuthenticatorException.InvalidName) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to invalid name",
            reason = UpdateEntryReason.InvalidEntryTitle,
            tag = TAG
        )
    } catch (e: AuthenticatorException.InvalidSecret) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to invalid secret",
            reason = UpdateEntryReason.InvalidEntrySecret,
            tag = TAG
        )
    } catch (e: IllegalArgumentException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to invalid secret format",
            reason = UpdateEntryReason.InvalidEntrySecret,
            tag = TAG
        )
    } catch (e: AuthenticatorException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to authenticator exception",
            reason = UpdateEntryReason.Unknown,
            tag = TAG
        )
    } catch (e: IllegalStateException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not update entry due to entry not found",
            reason = UpdateEntryReason.EntryNotFound,
            tag = TAG
        )
    }

    private companion object {

        private const val TAG = "UpdateEntryCommandHandler"

    }

}

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

package proton.android.authenticator.business.entries.application.create

import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.TimeoutException
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import kotlinx.coroutines.TimeoutCancellationException
import proton.android.authenticator.business.shared.domain.errors.ErrorLoggingUtils
import proton.android.authenticator.commonrust.AuthenticatorException
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.infrastructure.commands.CommandHandler
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import java.io.IOException
import javax.inject.Inject

internal class CreateEntryCommandHandler @Inject constructor(
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val creator: EntryCreator
) : CommandHandler<CreateEntryCommand, Unit, CreateEntryReason> {

    override suspend fun handle(command: CreateEntryCommand): Answer<Unit, CreateEntryReason> = try {
        when (command) {
            is CreateEntryCommand.FromYubiKeyTotp -> creator.createYubiKeyTotp(command)
            is CreateEntryCommand.FromYubiKeyUri -> command.toYubiKeyTotpCommand(authenticatorClient)
                .let { yubiKeyCommand -> creator.createYubiKeyTotp(yubiKeyCommand) }
            else -> command.toModel(authenticatorClient)
                .let { model -> creator.create(model = model) }
        }
        AuthenticatorLogger.i(TAG, "Successfully created entry")
        Answer.Success(Unit)
    } catch (e: TimeoutCancellationException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry due to timeout",
            reason = CreateEntryReason.YubiKeyTimeout,
            tag = TAG
        )
    } catch (e: TimeoutException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry due to YubiKey timeout",
            reason = CreateEntryReason.YubiKeyTimeout,
            tag = TAG
        )
    } catch (e: YubiKeyOathEnrollmentLockedError) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry because OATH is locked",
            reason = CreateEntryReason.YubiKeyLocked,
            tag = TAG
        )
    } catch (e: ApduException) {
        val reason = when (e.sw) {
            SW.NO_SPACE, SW.MEMORY_ERROR -> CreateEntryReason.YubiKeyStorageFull
            SW.AUTH_METHOD_BLOCKED, SW.SECURITY_CONDITION_NOT_SATISFIED -> CreateEntryReason.YubiKeyLocked
            else -> CreateEntryReason.YubiKeyError
        }
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry due to APDU error (SW: ${e.sw})",
            reason = reason,
            tag = TAG
        )
    } catch (e: CommandException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry due to YubiKey command error",
            reason = CreateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: IOException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry due to I/O error",
            reason = CreateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: YubiKeyOathCredentialNotFoundError) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create YubiKey-backed entry: credential not found",
            reason = CreateEntryReason.YubiKeyError,
            tag = TAG
        )
    } catch (e: AuthenticatorException.InvalidName) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create entry due to invalid name",
            reason = CreateEntryReason.InvalidEntryTitle,
            tag = TAG
        )
    } catch (e: AuthenticatorException.InvalidSecret) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create entry due to invalid secret",
            reason = CreateEntryReason.InvalidEntrySecret,
            tag = TAG
        )
    } catch (e: IllegalArgumentException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create entry due to invalid secret format",
            reason = CreateEntryReason.InvalidEntrySecret,
            tag = TAG
        )
    } catch (e: AuthenticatorException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create entry due to authenticator exception",
            reason = CreateEntryReason.Unknown,
            tag = TAG
        )
    } catch (e: IllegalStateException) {
        ErrorLoggingUtils.logAndReturnFailure(
            throwable = e,
            message = "Could not create entry due to save failure",
            reason = CreateEntryReason.CannotSaveEntry,
            tag = TAG
        )
    }

    private companion object {
        private const val TAG = "CreateEntryCommandHandler"
    }

}

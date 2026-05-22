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

package proton.android.authenticator.business.entrycodes.application.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import proton.android.authenticator.business.entrycodes.domain.EntryCode
import proton.android.authenticator.AuthenticatorCodeResponseModel
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.commonrust.MobileTotpGeneratorCallback
import proton.android.authenticator.commonrust.MobileTotpGeneratorInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.domain.providers.TimeProvider
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

internal class EntryCodesSearcher @Inject constructor(
    private val authenticatorClient: AuthenticatorMobileClientInterface,
    private val totpGenerator: MobileTotpGeneratorInterface,
    private val appDispatchers: AppDispatchers,
    private val timeProvider: TimeProvider,
    private val yubiKeyOathCodeProvider: YubiKeyOathCodeProvider
) {

    internal fun search(uris: List<String>): Flow<List<EntryCode>> {
        val indexedUris = uris.mapIndexed(::IndexedUri)
        val localUris = indexedUris.filterNot(IndexedUri::isYubiKeyOath)
        val yubiKeyUris = indexedUris.filter(IndexedUri::isYubiKeyOath)

        return combine(
            searchLocal(localUris),
            searchYubiKeyOath(yubiKeyUris)
        ) { localCodes, yubiKeyCodes ->
            indexedUris.map { indexedUri ->
                requireNotNull(localCodes[indexedUri.index] ?: yubiKeyCodes[indexedUri.index]) {
                    "Missing code for entry URI at index ${indexedUri.index}"
                }
            }
        }
    }

    private fun searchLocal(indexedUris: List<IndexedUri>): Flow<Map<Int, EntryCode>> {
        if (indexedUris.isEmpty()) return flowOf(emptyMap())

        return searchLocalUris(indexedUris.map(IndexedUri::uri))
            .map { codes ->
                indexedUris.zip(codes).associate { (indexedUri, code) -> indexedUri.index to code }
            }
    }

    private fun searchLocalUris(uris: List<String>): Flow<List<EntryCode>> = callbackFlow {
        coroutineScope {
            uris.map { uri ->
                async(appDispatchers.default) {
                    authenticatorClient.entryFromUri(uri)
                }
            }
        }
            .awaitAll()
            .let { entryModels ->
                if (entryModels.isEmpty()) {
                    trySend(emptyList<EntryCode>()).also { awaitClose() }
                } else {
                    totpGenerator.start(
                        entries = entryModels,
                        callback = object : MobileTotpGeneratorCallback {
                            override fun onCodes(codes: List<AuthenticatorCodeResponseModel>) {
                                codes.map { entryCodeResponse ->
                                    EntryCode(
                                        currentCode = entryCodeResponse.currentCode,
                                        nextCode = entryCodeResponse.nextCode
                                    )
                                }.also(::trySend)
                            }
                        }
                    ).also { handle ->
                        awaitClose {
                            handle.cancel()
                        }
                    }
                }
            }
    }.flowOn(appDispatchers.io)

    private fun searchYubiKeyOath(indexedUris: List<IndexedUri>): Flow<Map<Int, EntryCode>> {
        if (indexedUris.isEmpty()) return flowOf(emptyMap())

        return flow {
            while (currentCoroutineContext().isActive) {
                indexedUris.associate { indexedUri ->
                    val code = runCatching {
                        yubiKeyOathCodeProvider.calculate(
                            uri = indexedUri.uri,
                            nowSeconds = timeProvider.currentSeconds()
                        )
                    }.getOrElse {
                        HARDWARE_CODE_UNAVAILABLE
                    }

                    indexedUri.index to code
                }.also { codes ->
                    emit(codes)
                }

                delay(YUBIKEY_POLL_INTERVAL)
            }
        }.flowOn(appDispatchers.io)
    }

    private data class IndexedUri(val index: Int, val uri: String) {
        internal fun isYubiKeyOath(): Boolean = uri.startsWith(YUBIKEY_OATH_URI_PREFIX)
    }

    private companion object {
        private const val YUBIKEY_OATH_URI_PREFIX = "yubikey-oath://"
        private val HARDWARE_CODE_UNAVAILABLE = EntryCode(
            currentCode = "------",
            nextCode = "------"
        )
        private val YUBIKEY_POLL_INTERVAL = 1.seconds
    }

}

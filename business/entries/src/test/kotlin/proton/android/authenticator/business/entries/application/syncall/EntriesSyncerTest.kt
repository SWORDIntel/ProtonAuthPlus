package proton.android.authenticator.business.entries.application.syncall

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.AuthenticatorEntryType
import proton.android.authenticator.business.entries.domain.EntriesApi
import proton.android.authenticator.business.entries.domain.EntriesRepository
import proton.android.authenticator.business.entries.domain.Entry
import proton.android.authenticator.business.entries.domain.EntryAlgorithm
import proton.android.authenticator.business.entries.domain.EntryCredentialBackend
import proton.android.authenticator.business.entries.domain.EntryRemote
import proton.android.authenticator.business.entries.domain.EntryType
import proton.android.authenticator.business.entries.domain.FetchEntriesResult
import proton.android.authenticator.commonrust.AuthenticatorMobileClientInterface
import proton.android.authenticator.commonrust.RemoteEntry
import proton.android.authenticator.commonrust.SyncOperationCheckerInterface
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.keys.EncryptionKey

class EntriesSyncerTest {

    private val api: EntriesApi = mockk(relaxed = true)
    private val authenticatorClient: AuthenticatorMobileClientInterface = mockk(relaxed = true)
    private val repository: EntriesRepository = mockk(relaxed = true)
    private val syncOperationChecker: SyncOperationCheckerInterface = mockk()
    private val encryptionContext: EncryptionContext = mockk()

    private val appDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private val encryptionContextProvider = object : EncryptionContextProvider {
        override suspend fun <R> withEncryptionContext(
            encryptionKey: EncryptionKey?,
            block: suspend EncryptionContext.() -> R
        ): R = encryptionContext.block()
    }

    private lateinit var syncer: EntriesSyncer

    @Before
    fun setUp() {
        every { encryptionContext.decrypt(any<EncryptedByteArray>(), any()) } returns ByteArray(size = 32)
        syncer = EntriesSyncer(
            api = api,
            appDispatchers = appDispatchers,
            authenticatorClient = authenticatorClient,
            encryptionContextProvider = encryptionContextProvider,
            repository = repository,
            syncOperationChecker = syncOperationChecker
        )
    }

    @Test
    fun `remote copy of a locally hardware-backed entry is excluded from sync operations and sorting`() = runTest {
        val hardwareEntry = entry(id = HARDWARE_ID, backend = hardwareBackend())
        val localEntry = entry(id = LOCAL_ID)
        every { repository.findAll() } returns flowOf(listOf(hardwareEntry, localEntry))
        coEvery { api.fetchAll(any(), any()) } returns FetchEntriesResult(
            entries = listOf(
                remote(remoteId = "remote-hw", localId = HARDWARE_ID),
                remote(remoteId = "remote-local", localId = LOCAL_ID)
            )
        )
        coEvery { api.sortAll(any(), any(), any()) } returns SORT_TIME
        val remoteOperations = slot<List<RemoteEntry>>()
        every { syncOperationChecker.calculateOperations(capture(remoteOperations), any()) } returns emptyList()

        syncer.sync(
            userId = USER_ID,
            keys = listOf(SyncKey(id = "key-1", encryptedKey = EncryptedByteArray(byteArrayOf(1)))),
            entries = listOf(syncEntry(id = LOCAL_ID))
        )

        assertEquals(listOf("remote-local"), remoteOperations.captured.map { it.remoteId })
        coVerify(exactly = 1) { api.sortAll(USER_ID, any(), listOf("remote-local")) }
        coVerify(exactly = 0) { api.deleteAll(any(), any()) }
        coVerify(exactly = 0) { repository.removeAll(any()) }
    }

    @Test
    fun `remote entries are left untouched when there are no hardware-backed local entries`() = runTest {
        every { repository.findAll() } returns flowOf(listOf(entry(id = LOCAL_ID)))
        coEvery { api.fetchAll(any(), any()) } returns FetchEntriesResult(
            entries = listOf(remote(remoteId = "remote-local", localId = LOCAL_ID))
        )
        coEvery { api.sortAll(any(), any(), any()) } returns SORT_TIME
        val remoteOperations = slot<List<RemoteEntry>>()
        every { syncOperationChecker.calculateOperations(capture(remoteOperations), any()) } returns emptyList()

        syncer.sync(
            userId = USER_ID,
            keys = listOf(SyncKey(id = "key-1", encryptedKey = EncryptedByteArray(byteArrayOf(1)))),
            entries = listOf(syncEntry(id = LOCAL_ID))
        )

        assertEquals(1, remoteOperations.captured.size)
    }

    @Test
    fun `tombstoned hardware-backed entry still blocks its remote copy`() = runTest {
        val tombstone = entry(id = HARDWARE_ID, backend = hardwareBackend(), isDeleted = true)
        every { repository.findAll() } returns flowOf(listOf(tombstone))
        coEvery { api.fetchAll(any(), any()) } returns FetchEntriesResult(
            entries = listOf(remote(remoteId = "remote-hw", localId = HARDWARE_ID))
        )
        val remoteOperations = slot<List<RemoteEntry>>()
        every { syncOperationChecker.calculateOperations(capture(remoteOperations), any()) } returns emptyList()

        syncer.sync(
            userId = USER_ID,
            keys = listOf(SyncKey(id = "key-1", encryptedKey = EncryptedByteArray(byteArrayOf(1)))),
            entries = emptyList()
        )

        assertTrue(remoteOperations.captured.isEmpty())
    }

    private fun hardwareBackend() = EntryCredentialBackend.YubiKeyOath(
        credentialId = "cred-id",
        deviceId = "device-id",
        name = "name",
        issuer = "issuer",
        note = null,
        period = 30,
        algorithm = EntryAlgorithm.SHA1,
        digits = 6
    )

    private fun entry(
        id: String,
        backend: EntryCredentialBackend = EntryCredentialBackend.LocalEncrypted,
        isDeleted: Boolean = false
    ) = Entry(
        id = id,
        content = EncryptedByteArray(byteArrayOf()),
        createdAt = 1L,
        modifiedAt = 1L,
        isDeleted = isDeleted,
        isSynced = false,
        position = 0,
        credentialBackend = backend
    )

    private fun remote(remoteId: String, localId: String) = EntryRemote(
        id = remoteId,
        revision = 1,
        position = 0,
        createdAt = 1L,
        modifiedAt = 1L,
        model = AuthenticatorEntryModel(
            id = localId,
            name = "name",
            issuer = "issuer",
            secret = "JBSWY3DPEHPK3PXP",
            uri = "otpauth://totp/issuer:name?secret=JBSWY3DPEHPK3PXP",
            period = 30.toUShort(),
            note = null,
            entryType = AuthenticatorEntryType.TOTP
        )
    )

    private fun syncEntry(id: String) = SyncEntry(
        id = id,
        position = 0,
        modifyTime = 1L,
        name = "name",
        issuer = "issuer",
        note = null,
        period = 30,
        secret = "JBSWY3DPEHPK3PXP",
        type = EntryType.TOTP,
        uri = "otpauth://totp/issuer:name?secret=JBSWY3DPEHPK3PXP",
        isDeleted = false,
        isSynced = false
    )

    private companion object {
        const val USER_ID = "user-1"
        const val HARDWARE_ID = "hardware-1"
        const val LOCAL_ID = "local-1"
        const val SORT_TIME = 100L
    }
}

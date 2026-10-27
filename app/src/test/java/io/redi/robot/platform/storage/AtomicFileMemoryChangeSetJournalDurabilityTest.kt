package io.redi.robot.platform.storage

import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryLineage
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemoryScope
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.memory.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.memory.storage.MemoryChangeSetError
import io.redi.robot.memory.storage.MemoryChangeSetRecoveryResult
import io.redi.robot.memory.storage.MemoryChangeSetResult
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryJournalClearResult
import io.redi.robot.memory.storage.MemoryJournalFailure
import io.redi.robot.memory.storage.MemoryJournalReadResult
import io.redi.robot.memory.storage.MemoryJournalWriteResult
import io.redi.robot.memory.storage.MemoryLoadResult
import io.redi.robot.memory.storage.MemoryPersistenceError
import io.redi.robot.memory.storage.MemoryPersistResult
import io.redi.robot.memory.storage.MemoryProtectionResult
import io.redi.robot.memory.storage.MemoryRecordProtection
import io.redi.robot.memory.storage.MemoryRecordSlots
import io.redi.robot.memory.storage.MemorySlotFailure
import io.redi.robot.memory.storage.MemorySlotListResult
import io.redi.robot.memory.storage.MemorySlotReadResult
import io.redi.robot.memory.storage.MemorySlotResetResult
import io.redi.robot.memory.storage.MemorySlotWriteResult
import io.redi.robot.memory.storage.MemoryStoreKeyVersion
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class AtomicFileMemoryChangeSetJournalDurabilityTest {
    @Test
    fun `real journal clear returns Cleared only after the journal is absent`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-journal-clear").toFile()
        val journal = AtomicFileMemoryChangeSetJournal(directory)

        assertTrue(
            journal.writeIntent("intent".toByteArray()) is
                io.redi.robot.memory.storage.MemoryJournalWriteResult.Written,
        )
        val file = File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME)
        assertTrue(file.isFile)

        assertEquals(MemoryJournalClearResult.Cleared, journal.clearIntent())
        assertFalse(file.exists())
        assertFalse(removingFile(directory).exists())
        assertEquals(MemoryJournalReadResult.Empty, journal.readIntent())
    }

    @Test
    fun `failed real journal deletion keeps the removal blocked until delete succeeds`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-journal-delete-fault").toFile()
        var allowDelete = false
        val journal = AtomicFileMemoryChangeSetJournal(
            directory = directory,
            deleteJournalFile = { file -> if (allowDelete) file.delete() else false },
        )
        val slots = AtomicFileMemoryRecordSlots(directory)
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val correction = correctionFor(target, 2, "corrected")

        assertEquals(
            MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
            (store.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        // The removal tombstone keeps the removal in progress; the store stays blocked.
        assertTrue(removingFile(directory).isFile)
        assertEquals(
            MemoryPersistResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.persist(targetEnvelope(9)),
        )

        allowDelete = true
        assertEquals(MemoryPersistResult.Persisted, store.persist(targetEnvelope(9)))
        assertFalse(File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME).exists())
        assertFalse(removingFile(directory).exists())
    }

    @Test
    fun `directory sync failure fails closed with no durable completion reported`() {
        val directory = Files.createTempDirectory("hugo-dirsync-fail").toFile()
        val failing = FailingDirectorySync()
        val journal = AtomicFileMemoryChangeSetJournal(directory, failing)
        val slots = AtomicFileMemoryRecordSlots(directory, failing)

        assertEquals(
            MemoryJournalWriteResult.Failed(MemoryJournalFailure.IO_FAILED),
            journal.writeIntent("intent".toByteArray()),
        )
        assertEquals(
            MemorySlotWriteResult.Failed(MemorySlotFailure.IO_FAILED),
            slots.writeAtomically(recordId(1), ByteArray(4)),
        )
        // The renamed-but-unsynced journal may exist on disk, yet it is not durable: reads
        // fail closed instead of returning it, so no operation reports durable completion.
        assertEquals(
            MemoryJournalReadResult.Failed(MemoryJournalFailure.IO_FAILED),
            journal.readIntent(),
        )
    }

    @Test
    fun `failed removal durability keeps the store blocked and never reports cleared`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-clear-sync").toFile()
        // Seed a committed-but-uncleared change-set: the removal cannot finish.
        val seedJournal = AtomicFileMemoryChangeSetJournal(
            directory,
            deleteJournalFile = { false },
        )
        val seedStore = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(), seedJournal,
        )
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, seedStore.persist(target))
        val correction = correctionFor(target, 2, "corrected")
        assertEquals(
            MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
            (seedStore.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertTrue(removingFile(directory).isFile)

        // A fresh instance whose directory sync fails can neither complete the removal nor
        // replay anything: reads fail closed (the tombstone phase-2 sync happens before any
        // unlink), so the store stays blocked with zero progress.
        val blockedStore = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory),
            FakeProtection(),
            keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory, FailingDirectorySync()),
        )
        assertEquals(
            MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            blockedStore.load(target.recordId),
        )

        // An explicit clear on the failing instance reports failure, never Cleared.
        val failing = AtomicFileMemoryChangeSetJournal(directory, FailingDirectorySync())
        assertEquals(
            MemoryJournalClearResult.Failed(MemoryJournalFailure.IO_FAILED),
            failing.clearIntent(),
        )

        // A healthy fresh instance completes the removal durably and proceeds.
        val healed = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory),
            FakeProtection(),
            keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory),
        )
        assertEquals(MemoryPersistResult.Persisted, healed.persist(targetEnvelope(9)))
        assertFalse(removingFile(directory).exists())
        assertFalse(File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME).exists())
    }

    @Test
    fun `platform without a directory durability primitive fails closed`() {
        Assume.assumeFalse(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-dirsync-unsupported").toFile()
        val journal = AtomicFileMemoryChangeSetJournal(directory)
        val slots = AtomicFileMemoryRecordSlots(directory)

        assertEquals(
            MemoryJournalWriteResult.Failed(MemoryJournalFailure.IO_FAILED),
            journal.writeIntent("intent".toByteArray()),
        )
        assertEquals(
            MemorySlotWriteResult.Failed(MemorySlotFailure.IO_FAILED),
            slots.writeAtomically(recordId(1), ByteArray(4)),
        )
    }

    @Test
    fun `durable redo ordering syncs journal before record slots and journal removal last`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-journal-order").toFile()
        val events = mutableListOf<String>()
        val journalDurability = RecordingDurability("journal", events)
        val slotDurability = RecordingDurability("slot", events)
        val journal = AtomicFileMemoryChangeSetJournal(
            directory,
            journalDurability,
            MemoryDurabilityFaultInjector { stage -> events += "journal-stage:$stage" },
        )
        val slots = AtomicFileMemoryRecordSlots(
            directory,
            slotDurability,
            MemoryDurabilityFaultInjector { stage -> events += "slot-stage:$stage" },
        )
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        events.clear()

        val correction = correctionFor(target, 2, "corrected")
        assertEquals(
            MemoryChangeSetResult.Applied,
            store.persistSupersessionChangeSet(correction, target, 3_000L),
        )

        val journalDurable =
            events.indexOf("journal-stage:${MemoryDurabilityStage.JOURNAL_DIRECTORY_SYNCED}")
        val firstRecordSync = events.indexOf("slot:file-sync")
        val lastRecordDurable =
            events.lastIndexOf("slot-stage:${MemoryDurabilityStage.RECORD_DIRECTORY_SYNCED}")
        val journalDelete =
            events.indexOf("journal-stage:${MemoryDurabilityStage.JOURNAL_DELETED}")
        val journalDeleteDurable =
            events.indexOf("journal-stage:${MemoryDurabilityStage.JOURNAL_DELETE_DIRECTORY_SYNCED}")

        assertTrue(journalDurable >= 0)
        assertTrue(journalDurable < firstRecordSync)
        assertTrue(lastRecordDurable < journalDelete)
        assertTrue(journalDelete < journalDeleteDurable)
    }

    @Test
    fun `interruption after journal rename writes no record slot and restart recovers from redo intent`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-journal-restart").toFile()
        val normalSlots = AtomicFileMemoryRecordSlots(directory)
        val target = targetEnvelope(1)
        val seedStore = MemoryEnvelopeStore(normalSlots, FakeProtection(), keyVersion())
        assertEquals(MemoryPersistResult.Persisted, seedStore.persist(target))

        var failOnce = true
        val faultyJournal = AtomicFileMemoryChangeSetJournal(
            directory,
            faultInjector = MemoryDurabilityFaultInjector { stage ->
                if (stage == MemoryDurabilityStage.JOURNAL_RENAMED && failOnce) {
                    failOnce = false
                    throw SimulatedCrash()
                }
            },
        )
        val store = MemoryEnvelopeStore(normalSlots, FakeProtection(), keyVersion(), faultyJournal)
        val correction = correctionFor(target, 2, "corrected")
        assertTrue(
            store.persistSupersessionChangeSet(correction, target, 3_000L) is
                MemoryChangeSetResult.Failed,
        )

        val successorSlot = normalSlots.read(correction.recordId)
        assertTrue(successorSlot is io.redi.robot.memory.storage.MemorySlotReadResult.Missing)
        assertTrue(File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME).isFile)

        val restarted = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory),
            FakeProtection(),
            keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory),
        )
        assertTrue(restarted.recoverChangeSet() is MemoryChangeSetRecoveryResult.Recovered)
        assertEquals(MemoryDisposition.SUPERSEDED, loaded(restarted, target.recordId).disposition)
        assertEquals("corrected", loaded(restarted, correction.recordId).content?.canonicalValue)
    }

    @Test
    fun `interruption after a record rename leaves redo intent and restart converges`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-slot-restart").toFile()
        val target = targetEnvelope(1)
        val seedSlots = AtomicFileMemoryRecordSlots(directory)
        val seedStore = MemoryEnvelopeStore(seedSlots, FakeProtection(), keyVersion())
        assertEquals(MemoryPersistResult.Persisted, seedStore.persist(target))

        var failOnce = true
        val faultySlots = AtomicFileMemoryRecordSlots(
            directory,
            faultInjector = MemoryDurabilityFaultInjector { stage ->
                if (stage == MemoryDurabilityStage.RECORD_RENAMED && failOnce) {
                    failOnce = false
                    throw SimulatedCrash()
                }
            },
        )
        val journal = AtomicFileMemoryChangeSetJournal(directory)
        val store = MemoryEnvelopeStore(faultySlots, FakeProtection(), keyVersion(), journal)
        val correction = correctionFor(target, 2, "corrected")

        assertEquals(
            MemoryChangeSetError.STORAGE_IO_FAILED,
            (store.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertTrue(journal.readIntent() is MemoryJournalReadResult.Content)

        val restarted = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory),
            FakeProtection(),
            keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory),
        )
        assertTrue(restarted.recoverChangeSet() is MemoryChangeSetRecoveryResult.Recovered)
        assertEquals(MemoryDisposition.SUPERSEDED, loaded(restarted, target.recordId).disposition)
        assertEquals("corrected", loaded(restarted, correction.recordId).content?.canonicalValue)
    }

    @Test
    fun `failed journal removal durability blocks the next operation until directory sync completes`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-clear-sync").toFile()
        var failOnce = true
        val journal = AtomicFileMemoryChangeSetJournal(
            directory,
            faultInjector = MemoryDurabilityFaultInjector { stage ->
                if (stage == MemoryDurabilityStage.JOURNAL_DELETED && failOnce) {
                    failOnce = false
                    throw SimulatedCrash()
                }
            },
        )
        val store = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory),
            FakeProtection(),
            keyVersion(),
            journal,
        )
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val correction = correctionFor(target, 2, "corrected")

        assertEquals(
            MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
            (store.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertFalse(File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME).exists())

        // The next operation first completes the pending delete-directory sync barrier.
        assertEquals(MemoryPersistResult.Persisted, store.persist(targetEnvelope(9)))
    }

    @Test
    fun `fresh instance re-establishes journal durability before any replay slot write`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-restart-barrier").toFile()
        val seedStore = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(),
        )
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, seedStore.persist(target))

        // Crash after the journal rename but before its directory sync: the journal is on
        // disk but its directory entry was never durably established.
        var crashOnce = true
        val crashed = AtomicFileMemoryChangeSetJournal(
            directory,
            faultInjector = MemoryDurabilityFaultInjector { stage ->
                if (stage == MemoryDurabilityStage.JOURNAL_RENAMED && crashOnce) {
                    crashOnce = false
                    throw SimulatedCrash()
                }
            },
        )
        val interrupted = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(), crashed,
        )
        val correction = correctionFor(target, 2, "corrected")
        assertTrue(
            interrupted.persistSupersessionChangeSet(correction, target, 3_000L)
                is MemoryChangeSetResult.Failed,
        )
        assertTrue(File(directory, AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME).isFile)

        // A fresh instance's FIRST durability action must be the journal directory barrier —
        // before any replay slot write — with no reliance on any process-memory state.
        val events = mutableListOf<String>()
        val freshStore = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory, RecordingDurability("slot", events)),
            FakeProtection(),
            keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory, RecordingDurability("journal", events)),
        )
        assertEquals(
            MemoryChangeSetRecoveryResult.Recovered(2),
            freshStore.recoverChangeSet(),
        )
        val barrier = events.indexOf("journal:dir-sync")
        val firstSlotSync = events.indexOf("slot:file-sync")
        assertTrue("barrier must precede every replay slot write", barrier in 0 until firstSlotSync)
        assertEquals(MemoryDisposition.SUPERSEDED, loaded(freshStore, target.recordId).disposition)
        assertEquals("corrected", loaded(freshStore, correction.recordId).content?.canonicalValue)
    }

    @Test
    fun `failing durability barrier on a fresh instance causes zero replay slot writes`() {
        Assume.assumeTrue(directoryDurabilitySupported())
        val directory = Files.createTempDirectory("hugo-restart-fail").toFile()
        val seedStore = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(),
        )
        val target = targetEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, seedStore.persist(target))

        var crashOnce = true
        val crashed = AtomicFileMemoryChangeSetJournal(
            directory,
            faultInjector = MemoryDurabilityFaultInjector { stage ->
                if (stage == MemoryDurabilityStage.JOURNAL_RENAMED && crashOnce) {
                    crashOnce = false
                    throw SimulatedCrash()
                }
            },
        )
        val interrupted = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(), crashed,
        )
        val correction = correctionFor(target, 2, "corrected")
        assertTrue(
            interrupted.persistSupersessionChangeSet(correction, target, 3_000L)
                is MemoryChangeSetResult.Failed,
        )

        // A fresh instance that cannot establish the directory barrier fails closed and
        // writes absolutely nothing.
        val counting = CountingRealSlots(AtomicFileMemoryRecordSlots(directory))
        val blockedStore = MemoryEnvelopeStore(
            counting, FakeProtection(), keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory, FailingDirectorySync()),
        )
        assertEquals(
            MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            blockedStore.load(target.recordId),
        )
        assertTrue(blockedStore.recoverChangeSet() is MemoryChangeSetRecoveryResult.Failed)
        assertEquals(0, counting.writeCount)

        // A healthy retry/restart completes the recovery safely.
        val healthy = MemoryEnvelopeStore(
            AtomicFileMemoryRecordSlots(directory), FakeProtection(), keyVersion(),
            AtomicFileMemoryChangeSetJournal(directory),
        )
        assertEquals(MemoryChangeSetRecoveryResult.Recovered(2), healthy.recoverChangeSet())
        assertEquals(MemoryDisposition.SUPERSEDED, loaded(healthy, target.recordId).disposition)
        assertEquals("corrected", loaded(healthy, correction.recordId).content?.canonicalValue)
    }

    private fun directoryDurabilitySupported(): Boolean = try {
        val probe = Files.createTempDirectory("hugo-dirsync-probe").toFile()
        PlatformMemoryFileDurability.syncDirectory(probe)
        true
    } catch (_: Exception) {
        false
    }

    private fun removingFile(directory: File): File =
        File(
            directory,
            AtomicFileMemoryChangeSetJournal.JOURNAL_FILE_NAME +
                AtomicFileMemoryChangeSetJournal.REMOVING_SUFFIX,
        )

    /** Directory durability that always fails: the unsupported-platform stand-in. */
    private class FailingDirectorySync : MemoryFileDurability {
        override fun syncFile(output: FileOutputStream) {
            output.fd.sync()
        }

        override fun syncDirectory(directory: File) {
            throw java.io.IOException("directory durability unavailable")
        }
    }

    /** Counts successful durable writes through the real adapter. */
    private class CountingRealSlots(private val delegate: MemoryRecordSlots) : MemoryRecordSlots {
        var writeCount = 0

        override fun read(recordId: MemoryRecordId): MemorySlotReadResult = delegate.read(recordId)

        override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult {
            val result = delegate.writeAtomically(recordId, bytes)
            if (result is MemorySlotWriteResult.Written) writeCount++
            return result
        }

        override fun storedRecordIds(limit: Int, afterId: MemoryRecordId?): MemorySlotListResult =
            delegate.storedRecordIds(limit, afterId)

        override fun reset(): MemorySlotResetResult = delegate.reset()
    }

    private class RecordingDurability(
        private val label: String,
        private val events: MutableList<String>,
    ) : MemoryFileDurability {
        override fun syncFile(output: FileOutputStream) {
            output.fd.sync()
            events += "$label:file-sync"
        }

        override fun syncDirectory(directory: File) {
            PlatformMemoryFileDurability.syncDirectory(directory)
            events += "$label:dir-sync"
        }
    }

    private class SimulatedCrash : RuntimeException()

    private fun loaded(store: MemoryEnvelopeStore, recordId: MemoryRecordId): MemoryEnvelope =
        (store.load(recordId) as io.redi.robot.memory.storage.MemoryLoadResult.Found).envelope
}

private fun targetEnvelope(index: Int): MemoryEnvelope = MemoryEnvelope.create(
    recordId = recordId(index),
    ownerIdentity = owner(),
    layer = MemoryLayer.PERSONAL,
    type = token("preference"),
    scope = MemoryScope.GlobalPersonal,
    subjects = listOf(subject("answers")),
    content = textValue("original-$index"),
    provenance = userProvenance(),
    confidence = confidence(900),
    validationState = MemoryValidationState.VALIDATED,
    disposition = MemoryDisposition.ACTIVE,
    privacy = ContextPrivacyClassification.PRIVATE,
    allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
    retention = retention(),
    supersedes = null,
    conflictsWith = emptyList(),
    lineage = null,
    createdAtEpochSecond = 1_000L,
    updatedAtEpochSecond = null,
).success()

private fun correctionFor(
    target: MemoryEnvelope,
    index: Int,
    value: String,
): MemoryEnvelope = MemoryEnvelope.create(
    recordId = recordId(index),
    ownerIdentity = target.ownerIdentity,
    layer = target.layer,
    type = target.type,
    scope = target.scope,
    subjects = target.subjects,
    content = textValue(value),
    provenance = userProvenance(),
    confidence = confidence(1000),
    validationState = MemoryValidationState.VALIDATED,
    disposition = MemoryDisposition.ACTIVE,
    privacy = target.privacy,
    allowedUse = target.allowedUse,
    retention = target.retention ?: retention(),
    supersedes = target.recordId,
    conflictsWith = emptyList(),
    lineage = lineageOf(target.recordId),
    createdAtEpochSecond = 2_000L,
    updatedAtEpochSecond = null,
).success()

private fun lineageOf(recordId: MemoryRecordId): MemoryLineage =
    (MemoryLineage.create(listOf(recordId)) as MemoryDomainResult.Success).value

private fun retention(): MemoryRetention =
    (MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)
        as MemoryDomainResult.Success).value

private fun userProvenance(): MemoryProvenance =
    (MemoryProvenance.create(
        MemoryProvenanceOrigin.USER_EXPLICIT,
        modelGenerated = false,
        (ContextSourceType.parse("memory.correction") as ContextObservationResult.Success).value,
        (ContextSourceReference.parse("memory_correction") as ContextObservationResult.Success).value,
        (MemoryCreator.parse("user_correction") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

private fun keyVersion(): MemoryStoreKeyVersion =
    (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value

private fun recordId(index: Int): MemoryRecordId =
    MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

private fun owner(): HugoIdentityId =
    HugoIdentityId.fromUuid(UUID.fromString("ffffffff-0000-4000-8000-000000000001"))

private fun token(raw: String): MemoryTypeToken =
    (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value

private fun subject(raw: String): MemorySubjectToken =
    (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value

private fun textValue(raw: String): ContextValue =
    ContextValue.TextValue((ContextText.create(raw) as ContextObservationResult.Success).value)

private fun confidence(value: Int): ContextConfidence =
    (ContextConfidence.create(value) as ContextObservationResult.Success).value

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success: $error")
}

private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
    is MemoryProtectionResult.Success -> value
    is MemoryProtectionResult.Failure -> error("expected success: $error")
}

private class FakeProtection : MemoryRecordProtection {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ProtectedMemoryRecordPayload> =
        MemoryProtectionResult.Success(
            ProtectedMemoryRecordPayload(
                AUTHENTICATED_ENCRYPTION_ALGORITHM,
                keyVersion(),
                ByteArray(12) { (it + 1).toByte() },
                xor(plaintext),
            ),
        )

    override fun decrypt(
        payload: ProtectedMemoryRecordPayload,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ByteArray> =
        MemoryProtectionResult.Success(xor(payload.ciphertext))

    private fun xor(bytes: ByteArray): ByteArray =
        bytes.mapIndexed { index, byte ->
            (byte.toInt() xor (0x55 + index % 5)).toByte()
        }.toByteArray()
}

package io.redi.robot.platform.storage

import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.storage.MemorySlotListResult
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM verification of the H059 keyset-pagination contract on the real atomic-file slot
 * implementation: stable ascending ordering, strict `afterId` continuation (no duplicates, no
 * skips), and the bounded page size. Durability is not the subject here, so the slots run on a
 * test-scoped durability; the durable protocol is verified by
 * AtomicFileMemoryChangeSetJournalDurabilityTest.
 */
class AtomicFileMemoryRecordSlotsPaginationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `keyset pagination walks every record exactly once in ascending order`() {
        val slots = AtomicFileMemoryRecordSlots(tmp.newFolder(), PaginationTestDurability)
        val ids = (1..12).map { index -> recordId(index) }
        ids.forEach { id -> slots.writeAtomically(id, ByteArray(8) { 1 }) }

        var afterId: MemoryRecordId? = null
        val seen = mutableListOf<MemoryRecordId>()
        while (true) {
            val page = (slots.storedRecordIds(5, afterId) as MemorySlotListResult.Ids).recordIds
            assertTrue(page.size <= 5)
            if (page.isEmpty()) break
            assertTrue("strict continuation", afterId == null || page.first().value > afterId.value)
            seen.addAll(page)
            afterId = page.last()
        }
        assertEquals(ids, seen)
    }

    @Test
    fun `continuation never repeats the cursor and survives gaps`() {
        val slots = AtomicFileMemoryRecordSlots(tmp.newFolder(), PaginationTestDurability)
        val ids = (1..6).map { index -> recordId(index) }
        // Leave a gap: record 4 is absent, cursor 3 must still continue strictly past it.
        ids.filter { it != recordId(4) }.forEach { id -> slots.writeAtomically(id, ByteArray(4)) }

        val page = (slots.storedRecordIds(3, recordId(3)) as MemorySlotListResult.Ids).recordIds
        assertEquals(listOf(recordId(5), recordId(6)), page)

        val exhausted = (slots.storedRecordIds(3, recordId(6)) as MemorySlotListResult.Ids).recordIds
        assertTrue(exhausted.isEmpty())
    }

    @Test
    fun `page bound holds during continuation`() {
        val slots = AtomicFileMemoryRecordSlots(tmp.newFolder(), PaginationTestDurability)
        (1..10).map { index -> recordId(index) }.forEach { id -> slots.writeAtomically(id, ByteArray(4)) }

        val page = (slots.storedRecordIds(4, recordId(2)) as MemorySlotListResult.Ids).recordIds
        assertEquals(4, page.size)
        assertEquals(recordId(3), page.first())
        assertEquals(recordId(6), page.last())
    }

    @Test
    fun `null cursor preserves the original from-the-start listing`() {
        val slots = AtomicFileMemoryRecordSlots(tmp.newFolder(), PaginationTestDurability)
        val ids = (1..7).map { index -> recordId(index) }
        ids.forEach { id -> slots.writeAtomically(id, ByteArray(4)) }

        assertEquals(
            ids.take(3),
            (slots.storedRecordIds(3) as MemorySlotListResult.Ids).recordIds,
        )
        assertEquals(
            ids,
            (slots.storedRecordIds(10) as MemorySlotListResult.Ids).recordIds,
        )
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )
}

/** Test-scoped durability for the listing-contract tests; production slots use the strict port. */
private object PaginationTestDurability : MemoryFileDurability {
    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
    }

    override fun syncDirectory(directory: File) {
        // Listing semantics are not durability-sensitive; nothing to establish here.
    }
}

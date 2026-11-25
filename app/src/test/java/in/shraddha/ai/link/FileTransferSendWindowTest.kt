package `in`.shraddha.ai.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferSendWindowTest {
    @Test fun neverHasMoreThanEightUnacknowledgedChunks() {
        val window = FileTransferSendWindow()
        window.reset((0 until 20).toList())
        val first = window.nextBatch()
        assertEquals((0 until 8).toList(), first)
        assertEquals(8, window.inFlightCount())
        assertTrue(window.nextBatch().isEmpty())
        first.take(3).forEach { assertTrue(window.acknowledge(it)) }
        assertEquals(listOf(8, 9, 10), window.nextBatch())
        assertEquals(8, window.inFlightCount())
    }

    @Test fun duplicateOrUnknownAcknowledgementsDoNotFreeCapacity() {
        val window = FileTransferSendWindow()
        window.reset(listOf(4, 5))
        window.nextBatch()
        assertTrue(window.acknowledge(4))
        assertFalse(window.acknowledge(4))
        assertFalse(window.acknowledge(99))
        assertEquals(1, window.inFlightCount())
    }

    @Test fun completionRequiresQueueAndInflightSetToBeEmpty() {
        val window = FileTransferSendWindow()
        window.reset(listOf(1, 2))
        assertFalse(window.isComplete())
        window.nextBatch()
        assertTrue(window.acknowledge(1))
        assertTrue(window.acknowledge(2))
        assertTrue(window.isComplete())
    }

    @Test fun rejectsInvalidWindowAndDuplicateIndexes() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { FileTransferSendWindow(0) }
        val window = FileTransferSendWindow()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { window.reset(listOf(1, 1)) }
    }
}

package de.asdfghasdfgh.sokuseki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class PendingQueueTest {

    @Test
    fun `survives a restart and keeps FIFO order`() {
        val dir = Files.createTempDirectory("sokuseki-queue").toFile()
        val file = dir.resolve("pending.jsonl")

        PendingQueue(file).apply {
            add("""{"n":1}""")
            add("""{"n":2}""")
            add("""{"n":3}""")
        }

        val reopened = PendingQueue(file)
        assertEquals(3, reopened.size())
        assertEquals("""{"n":1}""", reopened.peek())
        reopened.removeFirst()
        assertEquals("""{"n":2}""", reopened.peek())

        val third = PendingQueue(file)
        assertEquals(2, third.size())
        third.removeFirst()
        third.removeFirst()
        assertNull(third.peek())
    }

    @Test
    fun `drops the oldest entry when full`() {
        val dir = Files.createTempDirectory("sokuseki-queue-max").toFile()
        val queue = PendingQueue(dir.resolve("pending.jsonl"), maxEntries = 2)
        queue.add("""{"n":1}""")
        queue.add("""{"n":2}""")
        queue.add("""{"n":3}""")

        assertEquals(2, queue.size())
        assertEquals("""{"n":2}""", queue.peek())
    }
}

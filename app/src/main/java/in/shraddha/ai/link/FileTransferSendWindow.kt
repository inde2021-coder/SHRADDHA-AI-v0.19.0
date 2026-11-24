package `in`.shraddha.ai.link

/** Keeps relay buffering bounded by requiring receiver ACKs before sending more chunks. */
internal class FileTransferSendWindow(private val capacity: Int = 8) {
    private val queued = ArrayDeque<Int>()
    private val inFlight = linkedSetOf<Int>()

    init { require(capacity in 1..64) }

    @Synchronized
    fun reset(indexes: List<Int>) {
        require(indexes.all { it >= 0 } && indexes.distinct().size == indexes.size)
        queued.clear()
        queued.addAll(indexes)
        inFlight.clear()
    }

    @Synchronized
    fun nextBatch(): List<Int> {
        val result = mutableListOf<Int>()
        while (queued.isNotEmpty() && inFlight.size < capacity) {
            val index = queued.removeFirst()
            check(inFlight.add(index))
            result += index
        }
        return result
    }

    @Synchronized
    fun acknowledge(index: Int): Boolean = inFlight.remove(index)

    @Synchronized
    fun inFlightCount(): Int = inFlight.size

    @Synchronized
    fun isComplete(): Boolean = queued.isEmpty() && inFlight.isEmpty()
}

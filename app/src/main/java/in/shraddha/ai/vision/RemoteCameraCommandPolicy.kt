package `in`.shraddha.ai.vision

/** Explicit service command gate; null remains a start for older in-app callers. */
object RemoteCameraCommandPolicy {
    enum class Command { START, STOP, REJECT }
    fun resolve(action: String?): Command = when (action) {
        null, RemoteCameraService.ACTION_START -> Command.START
        RemoteCameraService.ACTION_STOP -> Command.STOP
        else -> Command.REJECT
    }
}

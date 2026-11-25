package `in`.shraddha.ai.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.content.ContentValues
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** SAF-based, consent-gated 100 MiB file transfer workspace. */
class DeviceTransferActivity : Activity() {
    private lateinit var peer: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private var sourceUri: Uri? = null
    private var sourceName = ""
    private var sourceSize = 0L
    private var sourceHash = ""
    private var transferId = ""
    private var requestId = ""
    private var sourcePeer = ""
    private var incomingFile: File? = null
    private var incomingOut: RandomAccessFile? = null
    private var incomingChunks = 0
    private val receivedChunkIndexes = java.util.BitSet()
    private var pendingSaveUri: Uri? = null
    private var incomingVerified = false
    private var incomingCount = 0
    private var incomingSize = 0L
    private var incomingHash = ""
    private var incomingName = ""
    private var pendingIndexes: List<Int> = emptyList()
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val ackMonitor = Object()
    private val acknowledgedChunks = mutableSetOf<Int>()
    private val inFlightChunks = mutableSetOf<Int>()
    private var sendFailed = false
    private var sendThread: Thread? = null
    // Bound queued chunks so a fast/malicious peer cannot grow an unbounded in-memory queue.
    private val transferIo = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8), ThreadPoolExecutor.AbortPolicy())
    private var peerIdSnapshot = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(0xFF07131D.toInt()) }
        root.addView(TextView(this).apply { text = "DEVICE UNIVERSE • FILE TRANSFER"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Android ↔ paired device • SHA-256 integrity • 100 MiB maximum • explicit receiver approval"; textSize = 12f; setTextColor(0xFF73DDE7.toInt()); setPadding(0,8,0,14) })
        peer = EditText(this).apply {
            hint = "Paired device ID"; setTextColor(Color.WHITE); setHintTextColor(0xFF8BAFB8.toInt())
            setText(intent.getStringExtra("peer").orEmpty()); setPadding(16, 12, 16, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF102A36.toInt()); cornerRadius = 18f; setStroke(1, 0xFF2D8490.toInt())
            }
        }; root.addView(peer)
        peerIdSnapshot = peer.text.toString().trim()
        root.addView(button("CHOOSE FILE & SEND") { chooseSource() })
        root.addView(button("RESUME / RETRY TRANSFER") { resumeTransfer() })
        pauseButton = button("PAUSE TRANSFER") {
            paused.set(!paused.get())
            synchronized(ackMonitor) { ackMonitor.notifyAll() }
            pauseButton.text = if (paused.get()) "RESUME TRANSFER" else "PAUSE TRANSFER"
            status.text = if (paused.get()) "Paused locally. Resume to continue." else "Resuming transfer…"
        }
        root.addView(pauseButton)
        root.addView(button("CANCEL TRANSFER") { cancelTransfer() })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF38C7D2.toInt())
        }; root.addView(progress)
        status = TextView(this).apply { text = "Select a paired device ID. Receiver must enable Documents sharing and approve the incoming file."; setTextColor(0xFFE6F5F7.toInt()); setPadding(0,14,0,14) }; root.addView(status)
        root.addView(button("CLOSE") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
        restoreTransferState()
        DeviceLinkManager.onFileTransferMessage = { msg -> runOnUiThread { handleMessage(msg) } }
    }

    override fun onDestroy() {
        cancelled.set(true)
        paused.set(false)
        synchronized(ackMonitor) { ackMonitor.notifyAll() }
        // Stop accepting work, let already-queued durable writes finish, then close
        // the file handle. Do not enqueue cleanup into the bounded queue: it may be full.
        transferIo.shutdown()
        Thread({
            while (!transferIo.isTerminated) {
                try { transferIo.awaitTermination(1, TimeUnit.MINUTES) }
                catch (_: InterruptedException) { /* Continue cleanup; never close during a write. */ }
            }
            runCatching { incomingOut?.fd?.sync() }
            runCatching { incomingOut?.close() }
            incomingOut = null
        }, "ShraddhaTransferCleanup").apply { isDaemon = true; start() }
        if (DeviceLinkManager.onFileTransferMessage != null) DeviceLinkManager.onFileTransferMessage = null
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(0xFF04161C.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF38C7D2.toInt()); cornerRadius = 18f; setStroke(1, 0xFF8AF1F1.toInt())
        }
        elevation = 7f; setPadding(18, 12, 18, 12)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        setOnClickListener { action() }
    }

    private fun chooseSource() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 701)
    }

    private fun queryName(uri: Uri): String {
        var name = "shared-file"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        return name.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(120).ifBlank { "shared-file" }
    }

    private fun hashUri(uri: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= DeviceFileTransferProtocol.MAX_FILE_BYTES) { "Maximum file size is 100 MiB" }; digest.update(buffer, 0, n) }
        } ?: throw IllegalStateException("Cannot open selected file")
        require(total > 0) { "Empty files are not supported" }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 701) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceUri = uri; sourceName = queryName(uri); persistTransferState()
            status.text = "Calculating SHA-256 for $sourceName…"
            thread {
                try {
                    val (size, hash) = hashUri(uri); sourceSize = size; sourceHash = hash
                    runOnUiThread {
                        status.text = "Ready: $sourceName • $size bytes • SHA-256 verified locally. Request receiver permission?"
                        android.app.AlertDialog.Builder(this).setTitle("Send file to paired device?")
                            .setMessage("$sourceName\n$size bytes\nSHA-256: $hash\n\nReceiver will be asked to approve and choose a save location.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Request permission") { _, _ -> requestReceiverPermission() }.show()
                    }
                } catch (e: Exception) { runOnUiThread { status.text = "File error: ${e.message}" } }
            }
        } else if (requestCode == 702) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            pendingSaveUri = uri
            try {
                persistTransferState(synchronous = true)
            } catch (error: Exception) {
                status.text = "Could not persist the save destination; transfer was not acknowledged."
                return
            }
            val missing = synchronized(receivedChunkIndexes) {
                (0 until incomingCount).filterNot { receivedChunkIndexes.get(it) }
            }
            // Always send the persisted bitmap, including an empty missing list.
            // The relay reconstructs its positive-ACK map from this receiver-owned state.
            if (!DeviceLinkManager.requestFileTransferResume(sourcePeer, transferId, incomingCount, missing)) {
                status.text = "Save destination is ready, but resume request failed. Reconnect and tap Resume / Retry."
            } else if (missing.isEmpty()) {
                if (incomingVerified) enqueueTransferIo("save") { saveIncoming(uri) }
                else enqueueTransferIo("verify") { verifyIncoming() }
            } else {
                status.text = "Save destination selected. Requesting ${missing.size} missing chunks from sender…"
            }
        }
    }

    private fun launchIncomingSavePicker() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, incomingName),
            702
        )
    }

    private fun requestReceiverPermission() {
        val target = peer.text.toString().trim()
        peerIdSnapshot = target
        if (target.isBlank() || sourceUri == null) { status.text = "Select target device and file first."; return }
        requestId = UUID.randomUUID().toString().replace("-", "")
        persistTransferState()
        if (DeviceLinkManager.requestDataShare(target, "documents", requestId, sourceName)) status.text = "One-time Documents permission requested. Waiting for receiver approval…"
        else status.text = "Relay not connected; permission request could not be sent."
    }

    private fun beginSend() {
        val target = peerIdSnapshot.ifBlank { peer.text.toString().trim() }
        peerIdSnapshot = target
        val uri = sourceUri ?: return
        transferId = UUID.randomUUID().toString().replace("-", "")
        synchronized(ackMonitor) { acknowledgedChunks.clear(); inFlightChunks.clear(); sendFailed = false }
        persistTransferState()
        val chunks = DeviceFileTransferProtocol.chunkCount(sourceSize) ?: run { status.text = "Unsupported file size."; return }
        if (!DeviceLinkManager.startFileTransfer(target, requestId, transferId, sourceName, sourceSize, chunks, sourceHash)) { status.text = "Could not start transfer. Check relay and receiver permission."; return }
        status.text = "Transfer started. Waiting for receiver to accept and select destination…"
    }

    private fun sendChunk(index: Int) {
        val uri = sourceUri ?: throw IllegalStateException("Source file is no longer selected")
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot reopen source file")
        input.use {
            var skip = index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES
            while (skip > 0) { val n = it.skip(skip); if (n <= 0) throw IllegalStateException("Could not seek source file"); skip -= n }
            val buffer = ByteArray(DeviceFileTransferProtocol.CHUNK_BYTES); var count = 0
            while (count < buffer.size) { val n = it.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            val bytes = buffer.copyOf(count)
            try {
                require(bytes.isNotEmpty())
                if (!DeviceLinkManager.sendFileTransferChunk(peerIdSnapshot, transferId, index, bytes)) throw IllegalStateException("Relay send failed at chunk $index")
            } finally {
                bytes.fill(0)
                buffer.fill(0)
            }
        }
    }

    private fun startSending(indexes: List<Int>) {
        if (sendThread?.isAlive == true) return
        cancelled.set(false)
        sendThread = thread(name = "shraddha-file-send") {
            try {
                val count = DeviceFileTransferProtocol.chunkCount(sourceSize)
                    ?: throw IllegalStateException("Unsupported transfer size")
                val all = if (indexes.isEmpty()) (0 until count).toList() else indexes
                require(all.size <= count && all.distinct().size == all.size && all.all { it in 0 until count }) {
                    "Invalid chunk send list"
                }
                synchronized(ackMonitor) {
                    all.forEach { acknowledgedChunks.remove(it); inFlightChunks.remove(it) }
                    sendFailed = false
                }
                for ((batchNumber, batch) in all.chunked(8).withIndex()) {
                    while (paused.get() && !cancelled.get()) {
                        synchronized(ackMonitor) { ackMonitor.wait(250) }
                    }
                    if (cancelled.get()) break
                    synchronized(ackMonitor) { sendFailed = false }
                    for (index in batch) {
                        if (cancelled.get()) break
                        synchronized(ackMonitor) { inFlightChunks.add(index) }
                        try { sendChunk(index) }
                        catch (error: Exception) {
                            synchronized(ackMonitor) { inFlightChunks.remove(index) }
                            throw error
                        }
                    }
                    if (cancelled.get()) break
                    val deadline = System.currentTimeMillis() + 30_000L
                    synchronized(ackMonitor) {
                        while (!sendFailed && !cancelled.get() && !batch.all { it in acknowledgedChunks }) {
                            val remaining = deadline - System.currentTimeMillis()
                            if (remaining <= 0L) break
                            ackMonitor.wait(remaining.coerceAtMost(1000L))
                        }
                        if (sendFailed) throw IllegalStateException("Receiver rejected a chunk")
                        if (!cancelled.get() && !batch.all { it in acknowledgedChunks }) {
                            throw IllegalStateException("Timed out waiting for receiver ACKs")
                        }
                    }
                    val done = ((batchNumber * 8) + batch.size).coerceAtMost(all.size)
                    runOnUiThread {
                        progress.progress = done * 100 / all.size
                        status.text = "Receiver acknowledged $done/${all.size} requested chunks."
                    }
                }
                if (!cancelled.get()) runOnUiThread { status.text = "All requested chunks acknowledged. Waiting for receiver SHA-256 verification." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Send paused safely: ${e.message}. Reconnect and tap Resume / Retry." }
            }
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "connected" -> if (sourcePeer.isNotBlank() && transferId.isNotBlank()) resumeTransfer()
            "data_share_result" -> if (msg.optString("requestId") == requestId) {
                if (msg.optBoolean("approved")) beginSend() else { status.text = "Receiver denied Documents access."; clearTransferState() }
            }
            "file_transfer_start" -> {
                val sender = msg.optString("fromDeviceId")
                val offeredTransferId = msg.optString("transferId")
                val offeredName = DeviceFileTransferProtocol.safeDisplayName(msg.optString("fileName"))
                val rawSize = msg.opt("sizeBytes")
                val rawCount = msg.opt("chunkCount")
                val offeredSize = (rawSize as? Number)?.let {
                    if (it.toDouble() == it.toLong().toDouble()) it.toLong() else -1L
                } ?: -1L
                val offeredCount = (rawCount as? Number)?.let {
                    if (it.toDouble() == it.toLong().toDouble()) it.toInt() else -1
                } ?: -1
                val offeredHash = msg.optString("sha256")
                // Validate every untrusted manifest field before persisting state,
                // allocating a file, or presenting a save dialog. In particular,
                // transferId is used in a local filename and must never contain path
                // separators or traversal components.
                if (sender.isBlank() || !DeviceFileTransferProtocol.isValidManifest(
                        offeredTransferId, offeredSize, offeredCount, offeredHash
                    )) {
                    status.text = "Rejected invalid incoming transfer manifest."
                    if (sender.isNotBlank()) DeviceLinkManager.cancelFileTransfer(sender, offeredTransferId, "invalid_manifest")
                    return
                }
                val expectedPeer = if (transferId.isBlank()) peer.text.toString().trim() else peerIdSnapshot
                peerIdSnapshot = expectedPeer
                if (!DeviceFileTransferProtocol.isExpectedIncomingPeer(expectedPeer, sender)) {
                    status.text = "Rejected file transfer from an unexpected paired device."
                    DeviceLinkManager.cancelFileTransfer(sender, offeredTransferId, "unexpected_peer")
                    return
                }
                if (transferId.isNotBlank()) {
                    val sameTransfer = transferId == offeredTransferId && sourcePeer == sender
                    status.text = if (sameTransfer) {
                        "This transfer is already active. Resume it instead of resetting received chunks."
                    } else {
                        "Another transfer is already active. Finish or cancel it before accepting a new file."
                    }
                    if (!sameTransfer) DeviceLinkManager.cancelFileTransfer(sender, offeredTransferId, "receiver_busy")
                    return
                }
                cancelled.set(false)
                paused.set(false)
                sourcePeer = sender
                peerIdSnapshot = sender
                transferId = offeredTransferId
                incomingName = offeredName
                incomingSize = offeredSize
                incomingCount = offeredCount
                incomingHash = offeredHash.lowercase()
                incomingVerified = false
                receivedChunkIndexes.clear()
                incomingChunks = 0
                persistTransferState()
                android.app.AlertDialog.Builder(this).setTitle("Incoming file")
                    .setMessage("$incomingName • $incomingSize bytes\nFrom paired device $sourcePeer\nChoose whether to receive and save this file.")
                    .setNegativeButton("Reject") { _, _ -> DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "receiver_rejected"); clearTransferState() }
                    .setPositiveButton("Choose save location") { _, _ ->
                        try {
                            val inbox = File(filesDir, "shraddha-transfers").apply { mkdirs() }
                            incomingFile = File(inbox, "recv_$transferId.part")
                            incomingOut = RandomAccessFile(incomingFile, "rw").apply {
                                setLength(incomingSize)
                                fd.sync()
                            }
                            incomingChunks = 0
                            persistTransferState(synchronous = true)
                            launchIncomingSavePicker()
                        } catch (error: Exception) {
                            status.text = "Could not prepare a safe receive file: ${error.message}"
                            DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "receive_storage_error")
                            incomingFile?.delete()
                            clearTransferState()
                        }
                    }.show()
            }
            "file_transfer_chunk" -> {
                try {
                    transferIo.execute { receiveChunk(msg) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    status.text = "Receive queue is full; sender must retry this chunk."
                    val sender = msg.optString("fromDeviceId")
                    val offeredTransferId = msg.optString("transferId")
                    val rawIndex = msg.opt("index")
                    val index = (rawIndex as? Number)?.takeIf {
                        it.toDouble() == it.toLong().toDouble()
                    }?.toInt()
                    if (index != null && index in 0 until incomingCount &&
                        DeviceFileTransferProtocol.isExpectedPeerMessage(sourcePeer, sender, transferId, offeredTransferId)) {
                        DeviceLinkManager.acknowledgeFileTransfer(sender, offeredTransferId, index, ok = false)
                    }
                }
            }
            "file_transfer_ack" -> {
                if (!DeviceFileTransferProtocol.isExpectedPeerMessage(
                        peerIdSnapshot, msg.optString("fromDeviceId"),
                        transferId, msg.optString("transferId")
                    )) {
                    status.text = "Ignored file-transfer acknowledgement from an unexpected peer or transfer."
                    return
                }
                val rawIndex = msg.opt("index")
                if (rawIndex !is Number || rawIndex.toDouble() != rawIndex.toLong().toDouble()) return
                val index = rawIndex.toInt()
                val count = DeviceFileTransferProtocol.chunkCount(sourceSize)
                if (index == -1 && !msg.optBoolean("complete") && msg.optBoolean("ok") && sourceUri != null) {
                    status.text = "Receiver selected a destination. Sending file chunks with bounded ACK window…"
                    startSending(emptyList())
                } else if (index >= 0 && count != null && index < count) {
                    synchronized(ackMonitor) {
                        if (inFlightChunks.remove(index)) {
                            if (msg.optBoolean("ok")) acknowledgedChunks.add(index) else sendFailed = true
                            ackMonitor.notifyAll()
                        }
                    }
                    status.text = if (msg.optBoolean("ok")) "Receiver acknowledged chunk ${index + 1}." else "Receiver rejected chunk ${index + 1}."
                }
                if (msg.optBoolean("complete")) {
                    if (msg.optBoolean("ok")) {
                        cancelled.set(true)
                        synchronized(ackMonitor) { ackMonitor.notifyAll() }
                        enqueueTransferCleanup("Receiver verified and saved file successfully.", deletePartial = true)
                    } else status.text = "Receiver could not verify the file; resume after checking the destination."
                }
            }
            "file_transfer_resume" -> {
                // Only the sender may act on a resume request, and only for the
                // currently selected peer and the exact active transfer manifest.
                val expectedCount = DeviceFileTransferProtocol.chunkCount(sourceSize)
                if (sourceUri == null || !DeviceFileTransferProtocol.isExpectedPeerMessage(
                        peerIdSnapshot, msg.optString("fromDeviceId"),
                        transferId, msg.optString("transferId")
                    ) || expectedCount == null ||
                    (msg.opt("chunkCount") as? Number)?.let { it.toDouble() == it.toLong().toDouble() && it.toInt() == expectedCount } != true) {
                    status.text = "Rejected resume request: peer or transfer manifest mismatch."
                    return
                }
                val array = msg.optJSONArray("missingIndexes") ?: return
                if (array.length() > expectedCount) {
                    status.text = "Rejected resume request: too many chunk indexes."
                    return
                }
                val indexes = (0 until array.length()).mapNotNull { position ->
                    val raw = array.opt(position)
                    if (raw !is Number || raw.toDouble() != raw.toLong().toDouble()) null else raw.toInt()
                }
                if (indexes.size != array.length() || indexes.any { it !in 0 until expectedCount } || indexes.distinct().size != indexes.size) {
                    status.text = "Rejected resume request: invalid or duplicate chunk indexes."
                    return
                }
                if (indexes.isNotEmpty()) startSending(indexes)
                else status.text = "Receiver reports no missing chunks; waiting for final verification."
            }
            "file_transfer_cancel" -> {
                val expectedPeer = sourcePeer.ifBlank { peerIdSnapshot }
                if (!DeviceFileTransferProtocol.isExpectedPeerMessage(
                        expectedPeer, msg.optString("fromDeviceId"), transferId, msg.optString("transferId")
                    )) {
                    status.text = "Ignored cancellation from an unexpected peer or transfer."
                    return
                }
                cancelled.set(true)
                paused.set(false)
                synchronized(ackMonitor) { ackMonitor.notifyAll() }
                enqueueTransferCleanup("Transfer cancelled by paired device: ${msg.optString("reason").take(120)}", deletePartial = true)
            }
        }
    }

    private fun receiveChunk(msg: JSONObject) {
        if (cancelled.get()) return
        if (paused.get()) {
            runOnUiThread { status.text = "Incoming transfer paused locally; resume to accept more chunks." }
            return
        }
        try {
            require(msg.optString("fromDeviceId") == sourcePeer && sourcePeer.isNotBlank())
            require(msg.optString("transferId") == transferId && transferId.isNotBlank())
            val rawIndex = msg.opt("index")
            require(rawIndex is Number && rawIndex.toDouble() == rawIndex.toLong().toDouble())
            val index = rawIndex.toInt()
            require(index in 0 until incomingCount)
            val encodedPayload = msg.getString("payload")
            val bytes = android.util.Base64.decode(encodedPayload, android.util.Base64.NO_WRAP)
            try {
                require(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) == encodedPayload)
                val expected = DeviceFileTransferProtocol.expectedChunkBytes(incomingSize, incomingCount, index)
                    ?: throw IllegalArgumentException("Invalid chunk index or manifest")
                require(bytes.size == expected)
                synchronized(receivedChunkIndexes) {
                    if (!receivedChunkIndexes.get(index)) {
                        val output = incomingOut ?: throw IllegalStateException("Receive file is not open; retry after restoring the transfer.")
                        output.seek(index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES)
                        output.write(bytes)
                        output.fd.sync()
                        receivedChunkIndexes.set(index)
                        incomingChunks++
                        // Roll back the in-memory bitmap if durable state cannot be committed.
                        try {
                            persistTransferState(synchronous = true)
                        } catch (error: Exception) {
                            receivedChunkIndexes.clear(index)
                            incomingChunks--
                            throw error
                        }
                    } else {
                        // A verified receiver may see a retransmission when the prior ACK was lost.
                        // Its durable bitmap is sufficient to ACK this duplicate without reopening the file.
                        require(incomingVerified || incomingOut != null) {
                            "Receive file is not open; retry after restoring the transfer."
                        }
                    }
                }
                require(DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, index)) {
                    "Could not send receiver acknowledgement"
                }
                val current = incomingChunks
                runOnUiThread {
                    progress.progress = current * 100 / incomingCount
                    status.text = "Receiving $current/$incomingCount chunks • ${progress.progress}%"
                }
                if (!incomingVerified && incomingChunks >= incomingCount) verifyIncoming()
            } finally {
                bytes.fill(0)
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Receive failed: ${e.message}. Resume / retry after connection returns." }
        }
    }

    private fun saveIncoming(destination: Uri) {
        val file = incomingFile ?: return
        val savedName = incomingName
        var copiedCompletely = false
        try {
            check(!cancelled.get()) { "Transfer was cancelled before saving." }
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            } ?: throw IllegalStateException("Could not open selected destination")
            copiedCompletely = true
            if (cancelled.get()) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
                throw IllegalStateException("Transfer was cancelled while saving.")
            }
            if (!DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, -1, complete = true, ok = true)) {
                runOnUiThread { status.text = "Saved $savedName and verified, but completion acknowledgement could not be sent. Reconnect and tap Resume / Retry." }
                return
            }
            file.delete(); incomingFile = null; incomingOut?.close(); incomingOut = null
            clearTransferState()
            runOnUiThread { status.text = "Saved $savedName • SHA-256 verified • transfer complete." }
        } catch (e: Exception) {
            if (!copiedCompletely) runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
            // A revoked or unavailable Documents URI must not trap the transfer in an endless retry loop.
            val failedDestination = pendingSaveUri
            pendingSaveUri = null
            failedDestination?.let { uri ->
                runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            runCatching { persistTransferState(synchronous = true) }
            runOnUiThread { status.text = "Save failed: ${e.message}. Tap Resume / Retry to choose another save location." }
        }
    }

    private fun verifyIncoming() {
        if (cancelled.get()) return
        incomingOut?.fd?.sync(); incomingOut?.close(); incomingOut = null
        val file = incomingFile ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    if (cancelled.get()) return
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            } finally { buffer.fill(0) }
        }
        if (cancelled.get()) return
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != incomingHash) {
            runOnUiThread { status.text = "SHA-256 mismatch. File rejected." }
            DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "hash_mismatch")
            file.delete()
            clearTransferState()
            return
        }
        incomingVerified = true
        persistTransferState(synchronous = true)
        runOnUiThread { status.text = "All chunks received. SHA-256 verified. Saving to selected destination…" }
        pendingSaveUri?.let { saveIncoming(it) }
    }

    private fun resumeTransfer() {
        if (transferId.isBlank()) { status.text = "No active transfer to resume."; return }
        if (sourceUri != null && sourcePeer.isBlank()) {
            status.text = "Sender ready. The receiver must request resume to identify missing chunks."
        } else if (sourcePeer.isNotBlank() && incomingCount in 1..DeviceFileTransferProtocol.MAX_CHUNKS) {
            if (incomingFile == null || !incomingFile!!.exists()) {
                status.text = "The temporary receive file is missing. Cancel this transfer and request it again."
                return
            }
            if (pendingSaveUri == null) {
                launchIncomingSavePicker()
                return
            }
            val missing = synchronized(receivedChunkIndexes) { (0 until incomingCount).filterNot { receivedChunkIndexes.get(it) } }
            if (!DeviceLinkManager.requestFileTransferResume(sourcePeer, transferId, incomingCount, missing)) {
                status.text = "Could not send resume request. Verify the paired device and secure relay connection."
            } else if (missing.isEmpty()) {
                if (incomingVerified) enqueueTransferIo("save") { saveIncoming(pendingSaveUri!!) }
                else enqueueTransferIo("verify") { verifyIncoming() }
            } else {
                status.text = "Requesting " + missing.size + " missing chunks from the paired sender…"
            }
        } else status.text = "Reconnect to relay first, then retry."
    }

    private fun enqueueTransferIo(operation: String, task: () -> Unit) {
        try {
            transferIo.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runOnUiThread {
                status.text = "Transfer worker is busy; $operation was not queued. Wait briefly and tap Resume / Retry."
            }
        }
    }

    /** Persists only transfer metadata and chunk indexes; file bytes stay in app-private storage. */
    private fun persistTransferState(synchronous: Boolean = false) {
        if (!::peer.isInitialized) return
        val prefs = getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("peer", sourcePeer.ifBlank { peerIdSnapshot })
            .putString("sourceUri", sourceUri?.toString().orEmpty())
            .putString("sourceName", sourceName)
            .putLong("sourceSize", sourceSize)
            .putString("sourceHash", sourceHash)
            .putString("transferId", transferId)
            .putString("requestId", requestId)
            .putString("sourcePeer", sourcePeer)
            .putString("incomingFile", incomingFile?.absolutePath.orEmpty())
            .putInt("incomingCount", incomingCount)
            .putLong("incomingSize", incomingSize)
            .putString("incomingHash", incomingHash)
            .putString("incomingName", incomingName)
            .putString("receivedIndexes", synchronized(receivedChunkIndexes) { android.util.Base64.encodeToString(receivedChunkIndexes.toByteArray(), android.util.Base64.NO_WRAP) })
            .putBoolean("incomingVerified", incomingVerified)
            .putString("pendingSaveUri", pendingSaveUri?.toString().orEmpty())
        if (synchronous) check(editor.commit()) { "Could not persist file-transfer state" } else editor.apply()
    }

    private fun restoreTransferState() {
        val prefs = getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE)
        transferId = prefs.getString("transferId", "").orEmpty()
        if (transferId.isBlank()) return
        peer.setText(prefs.getString("peer", "").orEmpty())
        peerIdSnapshot = peer.text.toString().trim()
        sourceUri = prefs.getString("sourceUri", "").orEmpty().takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        sourceName = prefs.getString("sourceName", "").orEmpty()
        sourceSize = prefs.getLong("sourceSize", 0L)
        sourceHash = prefs.getString("sourceHash", "").orEmpty()
        requestId = prefs.getString("requestId", "").orEmpty()
        sourcePeer = prefs.getString("sourcePeer", "").orEmpty()
        incomingCount = prefs.getInt("incomingCount", 0)
        incomingSize = prefs.getLong("incomingSize", 0L)
        incomingHash = prefs.getString("incomingHash", "").orEmpty()
        incomingName = prefs.getString("incomingName", "").orEmpty()
        incomingVerified = prefs.getBoolean("incomingVerified", false)
        pendingSaveUri = prefs.getString("pendingSaveUri", "").orEmpty().takeIf { it.isNotBlank() }?.let(Uri::parse)
        val encodedIndexes = prefs.getString("receivedIndexes", "").orEmpty()
        if (encodedIndexes.isNotBlank()) {
            runCatching {
                val restored = java.util.BitSet.valueOf(android.util.Base64.decode(encodedIndexes, android.util.Base64.DEFAULT))
                require(incomingCount in 1..DeviceFileTransferProtocol.MAX_CHUNKS && restored.length() <= incomingCount) {
                    "Stored receive bitmap exceeds the declared chunk count."
                }
                receivedChunkIndexes.clear()
                receivedChunkIndexes.or(restored)
            }.onFailure {
                receivedChunkIndexes.clear()
                status.text = "Stored receive progress is invalid. Verify or cancel this transfer before retrying."
            }
        }
        incomingChunks = receivedChunkIndexes.cardinality()
        val savedFile = prefs.getString("incomingFile", "").orEmpty()
        if (savedFile.isNotBlank()) {
            val file = File(savedFile)
            val inbox = File(filesDir, "shraddha-transfers").canonicalFile
            if (file.exists() && file.canonicalPath.startsWith(inbox.path + File.separator)) {
                incomingFile = file
                incomingOut = runCatching { RandomAccessFile(file, "rw") }.getOrNull()
            }
        }
        status.text = "Previous transfer restored • $transferId • received $incomingChunks/$incomingCount chunks. Tap Resume / Retry after reconnect."
        // Do not verify/save until Resume / Retry sends the persisted bitmap to the relay.
        // This reconciles lost positive ACKs before a final completion request is sent.
    }

    private fun clearTransferState() {
        sourceUri?.let { uri -> runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        pendingSaveUri?.let { uri -> runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE).edit().clear().apply()
        synchronized(receivedChunkIndexes) { receivedChunkIndexes.clear() }
        synchronized(ackMonitor) { acknowledgedChunks.clear(); inFlightChunks.clear(); sendFailed = false; ackMonitor.notifyAll() }
        incomingChunks = 0
        incomingVerified = false
        pendingSaveUri = null
        sourceUri = null
        sourceName = ""
        sourceSize = 0L
        sourceHash = ""
        transferId = ""
        requestId = ""
        sourcePeer = ""
        incomingName = ""
        incomingSize = 0L
        incomingCount = 0
        incomingHash = ""
    }

    private fun enqueueTransferCleanup(message: String, deletePartial: Boolean) {
        val senderThread = sendThread
        Thread({
            // Stop any in-flight source read before clearing transfer identifiers or URIs.
            try { senderThread?.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            val cleanup = Runnable {
                if (deletePartial) {
                    runCatching { incomingOut?.fd?.sync() }
                    runCatching { incomingOut?.close() }
                    incomingOut = null
                    incomingFile?.delete()
                    incomingFile = null
                }
                clearTransferState()
                runOnUiThread { status.text = message }
            }
            while (!transferIo.isShutdown) {
                try {
                    transferIo.execute(cleanup)
                    return@Thread
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
                }
            }
            // If Activity teardown already shut the worker down, wait for all queued
            // writes to finish before touching the partial file or saved state.
            while (!transferIo.isTerminated) {
                try { transferIo.awaitTermination(1, TimeUnit.MINUTES) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
            }
            cleanup.run()
        }, "ShraddhaTransferCancel").apply { isDaemon = true; start() }
    }

    private fun cancelTransfer() {
        cancelled.set(true)
        paused.set(false)
        synchronized(ackMonitor) { ackMonitor.notifyAll() }
        val target = if (sourcePeer.isNotBlank()) sourcePeer else peerIdSnapshot
        val activeTransferId = transferId
        if (activeTransferId.isNotBlank() && target.isNotBlank()) {
            DeviceLinkManager.cancelFileTransfer(target, activeTransferId)
        }
        enqueueTransferCleanup("Transfer cancelled by user.", deletePartial = true)
    }
}

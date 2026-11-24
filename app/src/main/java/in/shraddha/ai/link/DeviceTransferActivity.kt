package `in`.shraddha.ai.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.provider.OpenableColumns
import android.content.ContentValues
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    private var lastAck = -1
    private var sendThread: Thread? = null

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
        root.addView(button("CHOOSE FILE & SEND") { chooseSource() })
        root.addView(button("RESUME / RETRY TRANSFER") { resumeTransfer() })
        pauseButton = button("PAUSE TRANSFER") { paused.set(!paused.get()); pauseButton.text = if (paused.get()) "RESUME TRANSFER" else "PAUSE TRANSFER"; status.text = if (paused.get()) "Paused locally. Resume to continue." else "Resuming transfer…" }
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
        runCatching { incomingOut?.fd?.sync() }
        runCatching { incomingOut?.close() }
        incomingOut = null
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
            persistTransferState()
            if (incomingVerified) saveIncoming(uri)
            else {
                DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, -1, complete = false, ok = true)
                status.text = "Save destination selected. Receiver ready; waiting for file chunks…"
            }
        }
    }

    private fun requestReceiverPermission() {
        val target = peer.text.toString().trim()
        if (target.isBlank() || sourceUri == null) { status.text = "Select target device and file first."; return }
        requestId = UUID.randomUUID().toString().replace("-", "")
        persistTransferState()
        if (DeviceLinkManager.requestDataShare(target, "documents", requestId, sourceName)) status.text = "One-time Documents permission requested. Waiting for receiver approval…"
        else status.text = "Relay not connected; permission request could not be sent."
    }

    private fun beginSend() {
        val target = peer.text.toString().trim(); val uri = sourceUri ?: return
        transferId = UUID.randomUUID().toString().replace("-", "")
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
            val buffer = ByteArray(DeviceFileTransferProtocol.CHUNK_BYTES)
            try {
                var count = 0
                while (count < buffer.size) { val n = it.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                val bytes = buffer.copyOf(count)
                try {
                    require(bytes.isNotEmpty())
                    if (!DeviceLinkManager.sendFileTransferChunk(peer.text.toString().trim(), transferId, index, bytes)) throw IllegalStateException("Relay send failed at chunk $index")
                } finally { bytes.fill(0) }
            } finally { buffer.fill(0) }
        }
    }

    private fun startSending(indexes: List<Int>) {
        if (sendThread?.isAlive == true) return
        cancelled.set(false)
        sendThread = thread(name = "shraddha-file-send") {
            try {
                val all = if (indexes.isEmpty()) (0 until (DeviceFileTransferProtocol.chunkCount(sourceSize) ?: 0)).toList() else indexes
                for ((position, index) in all.withIndex()) {
                    while (paused.get() && !cancelled.get()) Thread.sleep(200)
                    if (cancelled.get()) break
                    sendChunk(index)
                    runOnUiThread { progress.progress = ((position + 1) * 100 / all.size); status.text = "Sending chunks: ${position + 1}/${all.size} (chunk $index)" }
                    Thread.sleep(35)
                }
                if (!cancelled.get()) runOnUiThread { status.text = "All requested chunks sent. Waiting for receiver SHA-256 verification." }
            } catch (e: Exception) { runOnUiThread { status.text = "Send failed: ${e.message}. Tap Resume / Retry after reconnect." } }
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
                val offeredSize = msg.optLong("sizeBytes", -1L)
                val offeredCount = msg.optInt("chunkCount", -1)
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
                val expectedPeer = peer.text.toString().trim()
                if (expectedPeer.isNotBlank() && expectedPeer != sender) {
                    status.text = "Rejected file transfer from an unexpected paired device."
                    DeviceLinkManager.cancelFileTransfer(sender, offeredTransferId, "unexpected_peer")
                    return
                }
                sourcePeer = sender
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
                    .setMessage("${msg.optString("fileName")} • $incomingSize bytes\nFrom paired device $sourcePeer\nChoose whether to receive and save this file.")
                    .setNegativeButton("Reject") { _, _ -> DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "receiver_rejected"); clearTransferState() }
                    .setPositiveButton("Choose save location") { _, _ ->
                        val inbox = File(filesDir, "shraddha-transfers").apply { mkdirs() }
                        incomingFile = File(inbox, "recv_$transferId.part")
                        incomingOut = RandomAccessFile(incomingFile, "rw").apply { setLength(incomingSize) }
                        persistTransferState()
                        incomingChunks = 0
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, incomingName), 702)
                    }.show()
            }
            "file_transfer_chunk" -> receiveChunk(msg)
            "file_transfer_ack" -> {
                val index = msg.optInt("index", -1)
                if (index == -1 && !msg.optBoolean("complete") && msg.optBoolean("ok") && sourceUri != null) {
                    status.text = "Receiver selected a destination. Sending file chunks…"
                    startSending(emptyList())
                } else if (index >= 0) { lastAck = maxOf(lastAck, index); status.text = "Receiver acknowledged chunk ${index + 1}." }
                if (msg.optBoolean("complete")) { status.text = "Receiver verified and saved file successfully."; clearTransferState() }
            }
            "file_transfer_resume" -> {
                // Only the sender may act on a resume request, and only for the
                // currently selected peer and the exact active transfer manifest.
                val expectedCount = DeviceFileTransferProtocol.chunkCount(sourceSize)
                if (sourceUri == null || msg.optString("fromDeviceId") != peer.text.toString().trim() ||
                    msg.optString("transferId") != transferId || expectedCount == null ||
                    msg.optInt("chunkCount", -1) != expectedCount) {
                    status.text = "Rejected resume request: peer or transfer manifest mismatch."
                    return
                }
                val array = msg.optJSONArray("missingIndexes") ?: return
                if (array.length() > expectedCount) {
                    status.text = "Rejected resume request: too many chunk indexes."
                    return
                }
                val indexes = (0 until array.length()).map { array.optInt(it, -1) }
                if (indexes.any { it !in 0 until expectedCount } || indexes.distinct().size != indexes.size) {
                    status.text = "Rejected resume request: invalid or duplicate chunk indexes."
                    return
                }
                if (indexes.isNotEmpty()) startSending(indexes)
                else status.text = "Receiver reports no missing chunks."
            }
            "file_transfer_cancel" -> { status.text = "Transfer cancelled: ${msg.optString("reason")}"; clearTransferState() }
        }
    }

    private fun receiveChunk(msg: JSONObject) {
        if (paused.get()) { status.text = "Incoming transfer paused locally; resume to accept more chunks."; return }
        var decodedBytes: ByteArray? = null
        try {
            require(msg.optString("transferId") == transferId && incomingOut != null)
            val index = msg.getInt("index"); require(index in 0 until incomingCount)
            val bytes = android.util.Base64.decode(msg.getString("payload"), android.util.Base64.DEFAULT)
            decodedBytes = bytes
            val expected = DeviceFileTransferProtocol.expectedChunkBytes(incomingSize, incomingCount, index) ?: throw IllegalArgumentException("Invalid chunk index or manifest")
            require(bytes.size == expected)
            if (!receivedChunkIndexes.get(index)) { incomingOut!!.seek(index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES); incomingOut!!.write(bytes); receivedChunkIndexes.set(index); incomingChunks++; persistTransferState() }
            DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, index)
            progress.progress = (incomingChunks * 100 / incomingCount)
            status.text = "Receiving ${incomingChunks}/$incomingCount chunks • ${progress.progress}%"
            if (incomingChunks >= incomingCount) verifyIncoming()
        } catch (e: Exception) { status.text = "Receive failed: ${e.message}. Resume / retry after connection returns." }
        finally { decodedBytes?.fill(0) }
    }

    private fun saveIncoming(destination: Uri) {
        val file = incomingFile ?: return
        val savedName = incomingName
        try {
            contentResolver.openOutputStream(destination, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("Could not open selected destination")
            DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, -1, complete = true, ok = true)
            file.delete(); incomingFile = null; incomingOut?.close(); incomingOut = null
            clearTransferState()
            status.text = "Saved $savedName • SHA-256 verified • transfer complete."
        } catch (e: Exception) { status.text = "Save failed: ${e.message}" }
    }

    private fun verifyIncoming() {
        incomingOut?.fd?.sync(); incomingOut?.close(); incomingOut = null
        val file = incomingFile ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != incomingHash) { status.text = "SHA-256 mismatch. File rejected."; DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "hash_mismatch"); file.delete(); clearTransferState(); return }
        incomingVerified = true
        persistTransferState()
        status.text = "All chunks received. SHA-256 verified. Saving to selected destination…"
        pendingSaveUri?.let { saveIncoming(it) }
    }

    private fun resumeTransfer() {
        if (transferId.isBlank()) { status.text = "No active transfer to resume."; return }
        if (sourceUri != null && sourcePeer.isBlank()) {
            status.text = "Sender ready. The receiver must request resume to identify missing chunks."
        } else if (sourcePeer.isNotBlank() && incomingCount in 1..DeviceFileTransferProtocol.MAX_CHUNKS) {
            val missing = (0 until incomingCount).filterNot { receivedChunkIndexes.get(it) }
            if (missing.isEmpty()) {
                if (incomingVerified && pendingSaveUri != null) saveIncoming(pendingSaveUri!!)
                else status.text = "All chunks are already present. Verify or choose a save destination."
            } else if (DeviceLinkManager.requestFileTransferResume(sourcePeer, transferId, incomingCount, missing)) {
                status.text = "Requesting " + missing.size + " missing chunks from the paired sender…"
            } else status.text = "Could not send resume request. Verify the paired device and secure relay connection."
        } else status.text = "Reconnect to relay first, then retry."
    }

    /** Persists only transfer metadata and chunk indexes; file bytes stay in app-private storage. */
    private fun persistTransferState() {
        if (!::peer.isInitialized) return
        val prefs = getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE)
        prefs.edit()
            .putString("peer", peer.text.toString().trim())
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
            .putString("receivedIndexes", android.util.Base64.encodeToString(receivedChunkIndexes.toByteArray(), android.util.Base64.NO_WRAP))
            .putBoolean("incomingVerified", incomingVerified)
            .putString("pendingSaveUri", pendingSaveUri?.toString().orEmpty())
            .apply()
    }

    private fun restoreTransferState() {
        val prefs = getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE)
        transferId = prefs.getString("transferId", "").orEmpty()
        if (transferId.isBlank()) return
        peer.setText(prefs.getString("peer", "").orEmpty())
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
            runCatching { java.util.BitSet.valueOf(android.util.Base64.decode(encodedIndexes, android.util.Base64.DEFAULT)) }
                .onSuccess { receivedChunkIndexes.clear(); receivedChunkIndexes.or(it) }
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
        if (!incomingVerified && incomingCount > 0 && incomingChunks >= incomingCount && incomingFile?.exists() == true) {
            verifyIncoming()
        }
        if (incomingVerified && pendingSaveUri != null && incomingFile?.exists() == true) {
            runCatching { saveIncoming(pendingSaveUri!!) }
                .onFailure { status.text = "Verified file restored. Re-select the save destination to finish." }
        }
    }

    private fun clearTransferState() {
        sourceUri?.let { uri -> runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        pendingSaveUri?.let { uri -> runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        getSharedPreferences("shraddha_device_transfer", MODE_PRIVATE).edit().clear().apply()
        receivedChunkIndexes.clear()
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

    private fun cancelTransfer() {
        cancelled.set(true); paused.set(false)
        val target = if (sourcePeer.isNotBlank()) sourcePeer else peer.text.toString().trim()
        if (transferId.isNotBlank() && target.isNotBlank()) DeviceLinkManager.cancelFileTransfer(target, transferId)
        incomingOut?.close(); incomingOut = null; incomingFile?.delete(); incomingFile = null
        clearTransferState()
        status.text = "Transfer cancelled by user."
    }
}

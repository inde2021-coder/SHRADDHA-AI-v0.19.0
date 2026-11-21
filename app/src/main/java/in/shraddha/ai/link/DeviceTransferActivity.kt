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

/** SAF-based, consent-gated 100 MB file transfer workspace. */
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22,22,22,22); setBackgroundColor(0xFF080E1B.toInt()) }
        root.addView(TextView(this).apply { text = "DEVICE UNIVERSE • FILE TRANSFER"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Android ↔ paired device • SHA-256 integrity • 100 MB maximum • explicit receiver approval"; textSize = 12f; setTextColor(0xFFB8C5DC.toInt()); setPadding(0,8,0,14) })
        peer = EditText(this).apply { hint = "Paired device ID"; setTextColor(Color.WHITE); setHintTextColor(0xFF8997B0.toInt()); setText(intent.getStringExtra("peer").orEmpty()) }; root.addView(peer)
        root.addView(button("CHOOSE FILE & SEND") { chooseSource() })
        root.addView(button("RESUME / RETRY TRANSFER") { resumeTransfer() })
        pauseButton = button("PAUSE TRANSFER") { paused.set(!paused.get()); pauseButton.text = if (paused.get()) "RESUME TRANSFER" else "PAUSE TRANSFER"; status.text = if (paused.get()) "Paused locally. Resume to continue." else "Resuming transfer…" }
        root.addView(pauseButton)
        root.addView(button("CANCEL TRANSFER") { cancelTransfer() })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }; root.addView(progress)
        status = TextView(this).apply { text = "Select a paired device ID. Receiver must enable Documents sharing and approve the incoming file."; setTextColor(0xFFE4EAF6.toInt()); setPadding(0,14,0,14) }; root.addView(status)
        root.addView(button("CLOSE") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
        DeviceLinkManager.onFileTransferMessage = { msg -> runOnUiThread { handleMessage(msg) } }
    }

    override fun onDestroy() {
        if (DeviceLinkManager.onFileTransferMessage != null) DeviceLinkManager.onFileTransferMessage = null
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF5547A8.toInt())
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
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 100L * 1024 * 1024) { "Maximum file size is 100 MB" }; digest.update(buffer, 0, n) }
        } ?: throw IllegalStateException("Cannot open selected file")
        require(total > 0) { "Empty files are not supported" }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 701) {
            sourceUri = uri; sourceName = queryName(uri)
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
            pendingSaveUri = uri
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
        if (DeviceLinkManager.requestDataShare(target, "documents", requestId, sourceName)) status.text = "One-time Documents permission requested. Waiting for receiver approval…"
        else status.text = "Relay not connected; permission request could not be sent."
    }

    private fun beginSend() {
        val target = peer.text.toString().trim(); val uri = sourceUri ?: return
        transferId = UUID.randomUUID().toString().replace("-", "")
        val chunks = ((sourceSize + 49151) / 49152).toInt()
        if (!DeviceLinkManager.startFileTransfer(target, requestId, transferId, sourceName, sourceSize, chunks, sourceHash)) { status.text = "Could not start transfer. Check relay and receiver permission."; return }
        status.text = "Transfer started. Waiting for receiver to accept and select destination…"
    }

    private fun sendChunk(index: Int) {
        val uri = sourceUri ?: throw IllegalStateException("Source file is no longer selected")
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot reopen source file")
        input.use {
            var skip = index.toLong() * 49152L
            while (skip > 0) { val n = it.skip(skip); if (n <= 0) throw IllegalStateException("Could not seek source file"); skip -= n }
            val buffer = ByteArray(49152); var count = 0
            while (count < buffer.size) { val n = it.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            val bytes = buffer.copyOf(count)
            require(bytes.isNotEmpty())
            if (!DeviceLinkManager.sendFileTransferChunk(peer.text.toString().trim(), transferId, index, bytes)) throw IllegalStateException("Relay send failed at chunk $index")
        }
    }

    private fun startSending(indexes: List<Int>) {
        if (sendThread?.isAlive == true) return
        cancelled.set(false)
        sendThread = thread(name = "shraddha-file-send") {
            try {
                val all = if (indexes.isEmpty()) (0 until ((sourceSize + 49151) / 49152).toInt()).toList() else indexes
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
                if (msg.optBoolean("approved")) beginSend() else status.text = "Receiver denied Documents access."
            }
            "file_transfer_start" -> {
                sourcePeer = msg.optString("fromDeviceId"); transferId = msg.optString("transferId")
                incomingName = msg.optString("fileName"); incomingSize = msg.optLong("sizeBytes")
                incomingCount = msg.optInt("chunkCount"); incomingHash = msg.optString("sha256")
                if (incomingSize !in 1L..(100L * 1024 * 1024) || incomingCount !in 1..2200 || !incomingHash.matches(Regex("[a-f0-9]{64}"))) { status.text = "Rejected invalid incoming manifest."; return }
                android.app.AlertDialog.Builder(this).setTitle("Incoming file")
                    .setMessage("${msg.optString("fileName")} • $incomingSize bytes\nFrom paired device $sourcePeer\nChoose whether to receive and save this file.")
                    .setNegativeButton("Reject") { _, _ -> DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "receiver_rejected") }
                    .setPositiveButton("Choose save location") { _, _ ->
                        incomingFile = File(cacheDir, "recv_$transferId.part")
                        incomingOut = RandomAccessFile(incomingFile, "rw").apply { setLength(incomingSize) }
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
                if (msg.optBoolean("complete")) status.text = "Receiver verified and saved file successfully."
            }
            "file_transfer_resume" -> {
                val array = msg.optJSONArray("missingIndexes") ?: return
                val indexes = (0 until array.length()).map { array.optInt(it, -1) }.filter { it >= 0 }
                if (indexes.isNotEmpty()) startSending(indexes) else status.text = "Receiver reports no missing chunks."
            }
            "file_transfer_cancel" -> status.text = "Transfer cancelled: ${msg.optString("reason")}"
        }
    }

    private fun receiveChunk(msg: JSONObject) {
        if (paused.get()) { status.text = "Incoming transfer paused locally; resume to accept more chunks."; return }
        try {
            require(msg.optString("transferId") == transferId && incomingOut != null)
            val index = msg.getInt("index"); require(index in 0 until incomingCount)
            val bytes = android.util.Base64.decode(msg.getString("payload"), android.util.Base64.DEFAULT)
            val expected = if (index == incomingCount - 1) (incomingSize - index * 49152L).toInt() else 49152
            require(bytes.size == expected)
            if (!receivedChunkIndexes.get(index)) { incomingOut!!.seek(index.toLong() * 49152L); incomingOut!!.write(bytes); receivedChunkIndexes.set(index); incomingChunks++ }
            DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, index)
            progress.progress = (incomingChunks * 100 / incomingCount)
            status.text = "Receiving ${incomingChunks}/$incomingCount chunks • ${progress.progress}%"
            if (incomingChunks >= incomingCount) verifyIncoming()
        } catch (e: Exception) { status.text = "Receive failed: ${e.message}. Resume / retry after connection returns." }
    }

    private fun saveIncoming(destination: Uri) {
        val file = incomingFile ?: return
        try {
            contentResolver.openOutputStream(destination, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("Could not open selected destination")
            DeviceLinkManager.acknowledgeFileTransfer(sourcePeer, transferId, -1, complete = true, ok = true)
            file.delete(); incomingFile = null; incomingOut?.close(); incomingOut = null
            status.text = "Saved $incomingName • SHA-256 verified • transfer complete."
        } catch (e: Exception) { status.text = "Save failed: ${e.message}" }
    }

    private fun verifyIncoming() {
        incomingOut?.fd?.sync(); incomingOut?.close(); incomingOut = null
        val file = incomingFile ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != incomingHash) { status.text = "SHA-256 mismatch. File rejected."; DeviceLinkManager.cancelFileTransfer(sourcePeer, transferId, "hash_mismatch"); file.delete(); return }
        incomingVerified = true
        status.text = "All chunks received. SHA-256 verified. Saving to selected destination…"
        pendingSaveUri?.let { saveIncoming(it) }
    }

    private fun resumeTransfer() {
        if (transferId.isBlank()) { status.text = "No active transfer to resume."; return }
        if (sourceUri != null && sourcePeer.isBlank()) {
            status.text = "Sender ready. The receiver must request resume to identify missing chunks."
        } else if (sourcePeer.isNotBlank()) {
            DeviceLinkManager.requestFileTransferResume(sourcePeer, transferId)
            status.text = "Requesting missing chunks from sender…"
        } else status.text = "Reconnect to relay first, then retry."
    }

    private fun cancelTransfer() {
        cancelled.set(true); paused.set(false)
        val target = if (sourcePeer.isNotBlank()) sourcePeer else peer.text.toString().trim()
        if (transferId.isNotBlank() && target.isNotBlank()) DeviceLinkManager.cancelFileTransfer(target, transferId)
        incomingOut?.close(); incomingOut = null; incomingFile?.delete(); incomingFile = null
        status.text = "Transfer cancelled by user."
    }
}

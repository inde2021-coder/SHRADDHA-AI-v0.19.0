package com.Lia.assistant.agent.social

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Receives Android Shares and copies approved image/video content into a private inbox.
 * No external storage permission is required and untrusted URI names are never used as paths.
 */
class ShareToLiaActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inbox = File(filesDir, "SharedInbox").apply { mkdirs() }
        val imported = runCatching { importIncoming(inbox) }.getOrElse {
            Toast.makeText(this, "Media import failed: ${it.message ?: "unsupported share"}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        renderInbox(inbox, imported)
    }

    private fun importIncoming(inbox: File): List<File> {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) throw IllegalArgumentException("Unsupported share action")
        val uris = linkedSetOf<Uri>()
        intent?.data?.let(uris::add)
        intent?.clipData?.let { clip: ClipData ->
            if (clip.itemCount > SharedMediaPolicy.MAX_ITEMS) {
                throw IllegalArgumentException("Share up to ${SharedMediaPolicy.MAX_ITEMS} files at a time")
            }
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        val stream = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent?.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        }
        stream?.let(uris::add)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach(uris::add)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(uris::add)
        }
        if (uris.isEmpty()) throw IllegalArgumentException("No shared image or video was provided")
        if (uris.size > SharedMediaPolicy.MAX_ITEMS) throw IllegalArgumentException("Share up to ${SharedMediaPolicy.MAX_ITEMS} files at a time")

        val saved = mutableListOf<File>()
        var batchCopied = 0L
        try {
        uris.forEach { uri ->
            val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
            if (!SharedMediaPolicy.supports(mime)) {
                throw IllegalArgumentException("Only image and video files are supported")
            }
            val extension = SharedMediaPolicy.extensionFor(mime)
            val destination = File(inbox, "share_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}$extension")
            var copied = 0L
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Shared file cannot be opened")
                input.use { source ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            copied += count
                            batchCopied += count
                            if (copied > SharedMediaPolicy.MAX_FILE_BYTES) throw IllegalArgumentException("Each shared file must be 50 MB or smaller")
                            if (batchCopied > SharedMediaPolicy.MAX_BATCH_BYTES) throw IllegalArgumentException("The total share batch must be 100 MB or smaller")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (!SharedMediaPolicy.isAllowedSize(copied)) throw IllegalArgumentException("Shared file is empty or exceeds the size limit")
                saved += destination
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
        } catch (error: Exception) {
            saved.forEach { it.delete() }
            throw error
        }
        return saved
    }

    private fun renderInbox(inbox: File, imported: List<File>) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF07111D.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI • MEDIA INBOX"
            textSize = 18f
            setTextColor(0xFFEAF7FF.toInt())
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = if (imported.isEmpty()) "कोई नई media file import नहीं हुई। नीचे पहले से सुरक्षित files दिखाई गई हैं।"
            else "${imported.size} file(s) सुरक्षित रूप से app-private storage में import हुईं।"
            textSize = 13f
            setTextColor(0xFF8EDFE0.toInt())
            setPadding(0, 0, 0, 18)
        })
        val files = inbox.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "यहाँ image और video share करें। Files केवल Shraddha AI के private app storage में रखी जाती हैं।"
                setTextColor(0xFFCBD8E8.toInt())
                textSize = 14f
            })
        }
        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 14, 14, 14)
                setBackgroundColor(0xFF122337.toInt())
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.bottomMargin = 12
                layoutParams = lp
            }
            val isImage = file.extension in setOf("jpg", "png", "webp", "gif", "img")
            if (isImage) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()?.let { bitmap ->
                    card.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        maxHeight = 260
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "Imported image preview"
                    })
                }
            }
            card.addView(TextView(this).apply {
                text = "${file.name}\n${"%.2f".format(file.length() / (1024.0 * 1024.0))} MB"
                setTextColor(0xFFEAF7FF.toInt())
                textSize = 13f
                setPadding(0, 8, 0, 4)
            })
            card.addView(TextView(this).apply {
                text = "Saved privately • ${if (isImage) "Image" else "Video"}"
                setTextColor(0xFF8EDFE0.toInt())
                textSize = 11f
            })
            root.addView(card)
        }
        root.addView(TextView(this).apply {
            text = "Close"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(18, 16, 18, 16)
            setBackgroundColor(0xFF176174.toInt())
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}

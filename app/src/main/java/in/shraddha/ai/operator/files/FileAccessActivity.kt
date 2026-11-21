package `in`.shraddha.ai.operator.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.widget.*

/** User-granted Android file manager: search, open, copy and move through Storage Access Framework. */
class FileAccessActivity : Activity() {
    private val locator by lazy { DeviceFileLocator(this) }
    private lateinit var filename: EditText
    private lateinit var folder: EditText
    private lateinit var status: TextView
    private var selectedUri: android.net.Uri? = null
    private var selectedName = ""
    private var selectedMime = "*/*"
    private var destinationTree: android.net.Uri? = null
    private var latestMatches = emptyList<DeviceFileLocator.LocatedFile>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20); setBackgroundColor(0xFF0B1020.toInt()) }
        root.addView(TextView(this).apply { text = "SHRADDHA • FILE ACCESS"; textSize = 20f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text = "AI केवल आपके द्वारा चुने गए फ़ोल्डर/Android media permission में फ़ाइलें खोजेगा।"; setTextColor(0xFFB9A9FF.toInt()); setPadding(0,8,0,14) })
        root.addView(button("Media फ़ाइल permission माँगें") { requestMediaPermissions() })
        root.addView(button("फ़ोल्डर की अनुमति दें (एक बार)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 41) })
        filename = EditText(this).apply { hint = "उदाहरण: Rahul.jpg या invoice.pdf"; setTextColor(Color.WHITE); setHintTextColor(0xFF8894AE.toInt()) }; root.addView(filename)
        folder = EditText(this).apply { hint = "वैकल्पिक फ़ोल्डर संकेत: Download"; setTextColor(Color.WHITE); setHintTextColor(0xFF8894AE.toInt()) }; root.addView(folder)
        root.addView(button("फ़ाइल खोजें") { search() })
        root.addView(button("फ़ाइल चुनें (Open / Copy / Move)") { chooseSourceFile() })
        root.addView(button("चुनी हुई फ़ाइल खोलें") { openSelected() })
        root.addView(button("Destination folder चुनें") { chooseDestination() })
        root.addView(button("फ़ाइल Copy करें") { copyOrMove(false) })
        root.addView(button("फ़ाइल Move करें") { android.app.AlertDialog.Builder(this).setTitle("फ़ाइल Move करें?").setMessage("पहले destination में copy और size verification होगा। उसके बाद ही source delete करने की कोशिश होगी।").setNegativeButton("रद्द", null).setPositiveButton("Move") { _, _ -> copyOrMove(true) }.show() })
        status = TextView(this).apply { setTextColor(0xFFE5EAF5.toInt()); text = "फ़ाइल का पूरा नाम दें। समान नाम की कई फ़ाइलें हों तो सही फ़ाइल चुननी होगी।"; setPadding(0,16,0,16) }
        root.addView(status)
        root.addView(button("बंद करें") { finish() })
        val outer = ScrollView(this); outer.addView(root); setContentView(outer)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            41 -> data.data?.let { uri ->
                locator.rememberGrantedTree(uri, data.flags)
                destinationTree = uri
                status.text = "Folder permission मिली और destination चुना गया।"
            }
            43 -> data.data?.let { uri ->
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                selectedUri = uri
                selectedName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "selected_file"
                selectedMime = contentResolver.getType(uri) ?: "*/*"
                filename.setText(selectedName)
                status.text = "फ़ाइल चुनी गई: $selectedName"
            }
        }
    }

    private fun chooseSourceFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 43)
    }

    private fun chooseDestination() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ), 41)
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun openSelected() {
        val uri = selectedUri ?: run { status.text = "पहले फ़ाइल चुनें।"; return }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, selectedMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            status.text = "फ़ाइल को compatible app में खोलने का अनुरोध भेजा।"
        }.onFailure { status.text = "फ़ाइल नहीं खुली: ${it.message}" }
    }

    private fun copyOrMove(deleteSource: Boolean) {
        val source = selectedUri ?: run { status.text = "पहले source फ़ाइल चुनें।"; return }
        val tree = destinationTree ?: run { status.text = "पहले destination folder चुनें।"; return }
        val name = selectedName.ifBlank { "copied_file" }
        status.text = "फ़ाइल copy हो रही है…"
        Thread {
            val result = runCatching {
                val parent = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, tree) ?: error("Destination folder उपलब्ध नहीं")
                require(parent.isDirectory && parent.canWrite()) { "Destination folder में write permission नहीं है" }
                require(parent.findFile(name) == null) { "इसी नाम की फ़ाइल पहले से है; overwrite नहीं किया गया" }
                val created = parent.createFile(selectedMime, name) ?: error("Destination file create नहीं हुई")
                try {
                    contentResolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "Source file पढ़ी नहीं जा सकी" }
                        contentResolver.openOutputStream(created.uri, "w").use { output ->
                            requireNotNull(output) { "Destination file में लिख नहीं सके" }
                            input.copyTo(output, 64 * 1024)
                            output.flush()
                        }
                    }
                    val sourceLength = runCatching { contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
                    val destinationLength = runCatching { contentResolver.openAssetFileDescriptor(created.uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
                    if (sourceLength >= 0 && destinationLength >= 0) check(sourceLength == destinationLength) { "Copy size verification failed" }
                    if (deleteSource) {
                        val deleted = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, source)?.delete() == true
                        if (deleted) "move_complete:${created.uri}" else "copy_verified_source_delete_failed:${created.uri}"
                    } else "copy_complete:${created.uri}"
                } catch (error: Exception) {
                    created.delete()
                    throw error
                }
            }
            runOnUiThread {
                status.text = result.fold({ message ->
                    when {
                        message.startsWith("move_complete:") -> "Move पूरा। नई फ़ाइल: " + message.substringAfter(':')
                        message.startsWith("copy_verified_source_delete_failed:") -> "Copy सफल, पर source delete नहीं हुआ। दोनों फ़ाइलें सुरक्षित हैं। नई फ़ाइल: " + message.substringAfter(':')
                        else -> "Copy पूरा। नई फ़ाइल: " + message.substringAfter(':')
                    }
                }, { "File operation failed: ${it.message}" })
            }
        }.start()
    }

    private fun requestMediaPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
            Build.VERSION.SDK_INT >= 23 -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
            else -> emptyArray()
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions, 42)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) status.text = if (grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) "Media permission मिली। अब फ़ाइल खोजें या अधिक folders के लिए SAF अनुमति दें।" else "Media permission नहीं मिली; user-granted folder tree से फ़ाइल खोज सकते हैं।"
    }
    private fun search() {
        val result = locator.search(filename.text.toString(), folder.text.toString())
        latestMatches = result.matches
        status.text = result.message
        if (latestMatches.isNotEmpty()) {
            android.app.AlertDialog.Builder(this).setTitle("मिली हुई फ़ाइल चुनें")
                .setItems(latestMatches.map { "${it.displayName} • ${it.source} • ${it.relativePath}" }.toTypedArray()) { _, which ->
                    val item = latestMatches[which]
                    selectedUri = item.uri
                    selectedName = item.displayName
                    selectedMime = item.mimeType.ifBlank { "*/*" }
                    status.text = "फ़ाइल चुनी गई: ${item.displayName}"
                }.show()
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF5946A8.toInt()); setOnClickListener { action() } }
}

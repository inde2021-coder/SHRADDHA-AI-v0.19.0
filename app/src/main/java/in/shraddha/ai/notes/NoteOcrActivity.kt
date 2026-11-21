package `in`.shraddha.ai.notes

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.util.concurrent.Executors

/**
 * Private, on-device Notes + OCR workspace.
 * Image text is processed locally by ML Kit and is never uploaded by this activity.
 */
class NoteOcrActivity : Activity() {
    private val pickerRequest = 810
    private val worker = Executors.newSingleThreadExecutor()
    private var selectedImage: Uri? = null
    private lateinit var note: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(9, 13, 25))
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA • PRIVATE NOTES & OCR"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 18)
        })
        status = TextView(this).apply {
            text = "OCR इस डिवाइस पर होगा। आपकी image या note इस screen से cloud पर upload नहीं होगी।"
            textSize = 13f
            setTextColor(0xFFB9C5DD.toInt())
            setPadding(0, 0, 0, 12)
        }
        root.addView(status)
        root.addView(action("चित्र चुनें") { openImagePicker() })
        root.addView(action("चुने हुए चित्र से हिंदी / English OCR") { runOcr() })
        note = EditText(this).apply {
            hint = "यहाँ अपना note लिखें या OCR text सुधारें…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8793AA.toInt())
            setTextSize(16f)
            minLines = 9
            gravity = Gravity.TOP
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFF172238.toInt())
        }
        root.addView(note, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(action("नोट सुरक्षित करें") { saveNote() })
        root.addView(action("Clipboard में कॉपी करें") { copyNote() })
        root.addView(action("पहले सुरक्षित नोट खोलें") { loadNote() })
        root.addView(action("← वापस") { finish() })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun action(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { run() }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, pickerRequest)
    }

    @Deprecated("Legacy activity result is retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickerRequest || resultCode != RESULT_OK) return
        selectedImage = data?.data
        status.text = if (selectedImage != null) "चित्र चुना गया। OCR चलाने के लिए बटन दबाएँ।" else "कोई चित्र नहीं चुना गया।"
    }

    private fun runOcr() {
        val uri = selectedImage ?: run {
            Toast.makeText(this, "पहले एक image चुनें।", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "डिवाइस पर OCR चल रहा है…"
        worker.execute {
            val result = runCatching {
                val image = InputImage.fromFilePath(this, uri)
                val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                try {
                    val latinText = Tasks.await(latin.process(image)).text
                    val hindiText = Tasks.await(devanagari.process(image)).text
                    listOf(latinText, hindiText).filter { it.isNotBlank() }
                        .distinct().joinToString("\n\n").take(20000)
                } finally {
                    latin.close()
                    devanagari.close()
                }
            }
            runOnUiThread {
                if (result.isSuccess && !result.getOrNull().isNullOrBlank()) {
                    val extracted = result.getOrThrow()
                    note.setText(if (note.text.isNullOrBlank()) extracted else note.text.toString() + "\n\n" + extracted)
                    status.text = "OCR पूरा। Text editable है; save करने से पहले जाँच लें।"
                } else {
                    status.text = "OCR text नहीं मिला या image पढ़ी नहीं जा सकी: ${result.exceptionOrNull()?.message ?: "No readable text"}"
                }
            }
        }
    }

    private fun saveNote() {
        val value = note.text.toString().trim()
        if (value.isBlank()) {
            Toast.makeText(this, "खाली नोट सुरक्षित नहीं किया।", Toast.LENGTH_SHORT).show()
            return
        }
        val store = EncryptedFeatureStore(this)
        val old = runCatching { JSONArray(store.get("private_notes_items").getOrThrow().ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val items = JSONArray()
        items.put(JSONObject().put("text", value).put("updatedAt", System.currentTimeMillis()))
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (items.length() < 20) items.put(item)
        }
        val saved = store.put("private_notes_items", items.toString())
        if (saved.isSuccess) {
            status.text = "नोट इस डिवाइस के AES-GCM encrypted storage में सुरक्षित है।"
            Toast.makeText(this, "नोट encrypted रूप में सुरक्षित हुआ।", Toast.LENGTH_SHORT).show()
        } else {
            status.text = "नोट सुरक्षित नहीं हुआ: ${saved.exceptionOrNull()?.message ?: "storage error"}"
        }
    }

    private fun loadNote() {
        val items = runCatching {
            JSONArray(EncryptedFeatureStore(this).get("private_notes_items").getOrThrow().ifBlank { "[]" })
        }.getOrDefault(JSONArray())
        if (items.length() == 0) {
            Toast.makeText(this, "अभी कोई सुरक्षित नोट नहीं है।", Toast.LENGTH_SHORT).show()
            return
        }
        note.setText(items.optJSONObject(0)?.optString("text").orEmpty())
        status.text = "सबसे नया स्थानीय नोट खोला गया।"
    }

    private fun copyNote() {
        val value = note.text.toString()
        if (value.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Shraddha note", value))
        Toast.makeText(this, "नोट Clipboard में कॉपी हुआ।", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

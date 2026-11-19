package `in`.shraddha.ai.operator.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.widget.*

/** One-time user grant for folders that the AI may search for exact requested filenames. */
class FileAccessActivity : Activity() {
    private val locator by lazy { DeviceFileLocator(this) }
    private lateinit var filename: EditText
    private lateinit var folder: EditText
    private lateinit var status: TextView
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
        status = TextView(this).apply { setTextColor(0xFFE5EAF5.toInt()); text = "फ़ाइल का पूरा नाम दें। समान नाम की कई फ़ाइलें हों तो सही फ़ाइल चुननी होगी।"; setPadding(0,16,0,16) }
        root.addView(status)
        root.addView(button("बंद करें") { finish() })
        val outer = ScrollView(this); outer.addView(root); setContentView(outer)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode,resultCode,data); if (requestCode == 41 && resultCode == RESULT_OK) { val resultData = data ?: return; resultData.data?.let { locator.rememberGrantedTree(it, resultData.flags); status.text = "फ़ोल्डर की अनुमति सुरक्षित की गई। अब फ़ाइल खोज सकते हैं।" } } }
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
        status.text = result.message + "\n" + result.matches.joinToString("\n\n") { "${it.displayName}\n${it.mimeType}\n${it.relativePath}\n${it.uri}" }
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF5946A8.toInt()); setOnClickListener { action() } }
}

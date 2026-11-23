package `in`.shraddha.ai.operator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.Lia.assistant.agent.NovaAccessibilityService
import com.Lia.assistant.agent.core.AccessibilityScreenDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences

/**
 * Consent-gated image-to-form workflow. Every proposed field is editable and
 * individually approved; no form submission or save button is ever activated.
 */
class FormAutofillActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var formType: Spinner
    private lateinit var cloudConsent: CheckBox
    private lateinit var personalConsent: CheckBox
    private var imageUri: Uri? = null
    private var extracted: FormExtractionResult? = null
    private val rows = mutableListOf<Pair<ExtractedFormValue, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantName = NovaPreferences.getString(
            this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME
        ).trim().ifBlank { AssistantBrand.NAME }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(7, 19, 29))
        }
        root.addView(label("$assistantName • FORM ASSISTANT", 20f, Color.WHITE))
        root.addView(label("Image se fields nikalein • review karein • har field ko alag approve karein", 13f, 0xFF73DDE7.toInt()))
        formType = Spinner(this).apply {
            adapter = ArrayAdapter(this@FormAutofillActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Farmer", "Plot", "Boundary", "Land deed", "Consent", "Unknown"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF102A36.toInt()); cornerRadius = 16f; setStroke(1, 0xFF2D8490.toInt())
            }
            setPadding(14, 8, 14, 8)
        }
        root.addView(formType)
        cloudConsent = CheckBox(this).apply {
            text = "Main is image ko configured secure AI server par process karne ki anumati deta/deti hoon"
            setTextColor(Color.WHITE)
        }
        personalConsent = CheckBox(this).apply {
            text = "Image mein Aadhaar / phone / address jaise personal data ko process karne ki alag anumati"
            setTextColor(Color.WHITE)
        }
        root.addView(cloudConsent)
        root.addView(personalConsent)
        root.addView(action("Gallery se form image chunein") { chooseImage() })
        root.addView(action("AI se fields extract karein") { extractImage() })
        status = label("Status: Pehle image chunein. Accessibility permission zaroori hai.", 13f, 0xFFE6F5F7.toInt())
        root.addView(status)
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsContainer)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Legacy result API is retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK) {
            imageUri = data?.data
            status.text = if (imageUri != null) "Image select ho gayi. Consent check karke extraction karein." else "Image nahi mili."
        }
    }

    private fun extractImage() {
        val uri = imageUri
        if (uri == null) { status.text = "Pehle image chunein."; return }
        if (!cloudConsent.isChecked) { status.text = "Cloud processing consent zaroori hai."; return }
        val type = listOf("farmer", "plot", "boundary", "land_deed", "consent", "unknown")[formType.selectedItemPosition]
        val allowPersonalData = personalConsent.isChecked
        status.text = "Image process ho rahi hai…"
        worker.execute {
            val result = runCatching {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: error("Image open nahi hui.")
                try {
                    `in`.shraddha.ai.future.MainBrain.extractForm(
                        this, bitmap, type,
                        cloudProcessingConsent = true,
                        personalDataConsent = allowPersonalData
                    ).getOrThrow()
                } finally { bitmap.recycle() }
            }
            runOnUiThread {
                result.onSuccess { showReview(it) }
                    .onFailure { status.text = "Extraction error: ${it.message ?: "unknown"}" }
            }
        }
    }

    private fun showReview(result: FormExtractionResult) {
        extracted = result
        rows.clear()
        resultsContainer.removeAllViews()
        status.text = "Form: ${result.formType} • Provider: ${result.provider} • ${result.fields.size} fields. Koi field apne aap submit nahi hogi."
        result.warnings.forEach { resultsContainer.addView(label("⚠ $it", 12f, 0xFFFFC66D.toInt())) }
        result.fields.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF102A36.toInt()); cornerRadius = 16f; setStroke(1, 0xFF2D8490.toInt())
                }
            }
            card.addView(label("${item.label} • ${(item.confidence * 100).toInt()}% confidence • ${item.status}", 13f, Color.WHITE))
            if (item.sourceText.isNotBlank()) card.addView(label("Source: ${item.sourceText}", 11f, 0xFFB6CDD2.toInt()))
            val edit = EditText(this).apply {
                setText(item.value)
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF8BAFB8.toInt())
                hint = "Review / edit value"
                setSingleLine(false)
            }
            card.addView(edit)
            rows += item to edit
            card.addView(action("Is field ko approve karke fill karein") { approveField(item, edit.text.toString()) })
            resultsContainer.addView(card)
        }
        if (result.fields.isEmpty()) resultsContainer.addView(label("Koi field confidently extract nahi hui. Original form manually check karein.", 13f, 0xFFFFC66D.toInt()))
    }

    private fun approveField(item: ExtractedFormValue, value: String) {
        if (value.isBlank()) { status.text = "Blank value fill nahi ki ja sakti."; return }
        if (item.key in setOf("aadhaarNumber", "panNumber", "phoneNumber", "address") && !personalConsent.isChecked) {
            status.text = "Personal data consent nahi diya gaya. Is sensitive field ko fill nahi kiya."
            return
        }
        if (item.confidence < 0.90f || item.status != "ready") {
            AlertDialog.Builder(this).setTitle("Low confidence")
                .setMessage("AI confidence kam hai. Value ko manually verify karke hi proceed karein.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("I verified — continue") { _, _ -> fillApproved(item, value) }.show()
            return
        }
        AlertDialog.Builder(this).setTitle("Approve field?")
            .setMessage("${item.label}\n\n$value\n\nSirf ye field fill hogi. Form submit nahi hoga.")
            .setNegativeButton("Reject", null)
            .setPositiveButton("Approve & fill") { _, _ -> fillApproved(item, value) }.show()
    }

    private fun fillApproved(item: ExtractedFormValue, value: String) {
        if (!NovaAccessibilityService.isEnabled(this) || NovaAccessibilityService.instance == null) {
            status.text = "Pehle Shraddha Accessibility Service enable karein."
            return
        }
        status.text = "${item.label}: current form field match ho raha hai…"
        worker.execute {
            val outcome = runCatching {
                val result = runBlocking {
                    FormAutofillExecutor(AccessibilityScreenDriver()).applyApproved(
                        extractedValues = listOf(item.copy(value = value)),
                        approvedFieldKeys = setOf(item.key)
                    )
                }.singleOrNull() ?: error("Approved field was not processed.")
                if (!result.success) error(result.message)
                "Verified: ${item.label} fill ho gaya. Form submit nahi hua."
            }
            runOnUiThread { status.text = outcome.getOrElse { "Roka gaya: ${it.message ?: "unknown error"}" } }
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, 10, 0, 10)
    }

    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(0xFF04161C.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF38C7D2.toInt()); cornerRadius = 18f; setStroke(1, 0xFF8AF1F1.toInt())
        }
        elevation = 7f; setPadding(18, 12, 18, 12)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_IMAGE = 7041 }
}

package `in`.shraddha.ai.operator.learning

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.OperatorEvent
import `in`.shraddha.ai.operator.OperatorTaskState
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/** User-driven per-app training: teach semantic anchors and reusable, verifiable action recipes. */
class AppTrainingActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var appLabel: EditText
    private lateinit var role: Spinner
    private lateinit var target: EditText
    private lateinit var workflow: EditText
    private lateinit var action: Spinner
    private lateinit var inputKey: EditText
    private lateinit var expected: EditText
    private val pending = mutableListOf<LearnedStep>()
    private var currentPackage = ""
    private val store by lazy { AppProfileStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20); setBackgroundColor(0xFF0B1020.toInt()) }
        root.addView(TextView(this).apply { text = "SHRADDHA • APP LEARNING LAB"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text = "हर ऐप का अलग प्रोफ़ाइल • Semantic UI सीखना • सुरक्षित Workflow"; gravity = Gravity.CENTER; setTextColor(0xFFB8A8FF.toInt()); setPadding(0, 8, 0, 16) })
        appLabel = field("ऐप का नाम (पहले स्क्रीन देखें)"); root.addView(appLabel)
        root.addView(button("वर्तमान ऐप/स्क्रीन पहचानें") { captureApp() })
        target = field("स्क्रीन पर दिखने वाला सटीक बटन/फ़ील्ड नाम"); root.addView(target)
        role = spinner(listOf("button", "field", "label", "menu", "tab", "upload")); root.addView(role)
        root.addView(button("इस UI लक्ष्य को सीखकर सहेजें") { saveAnchor() })
        root.addView(TextView(this).apply { text = "वर्कफ़्लो ट्रेनिंग"; setTextColor(Color.WHITE); textSize = 17f; setPadding(0, 18, 0, 6) })
        workflow = field("वर्कफ़्लो नाम (जैसे नया ग्राहक जोड़ना)"); root.addView(workflow)
        action = spinner(listOf("CLICK", "TYPE", "SELECT_FILE", "BACK", "SCROLL")); root.addView(action)
        inputKey = field("TYPE के लिए Variable key / value (जैसे customer_name)"); root.addView(inputKey)
        expected = field("वैकल्पिक: चरण के बाद दिखने वाला अपेक्षित टेक्स्ट"); root.addView(expected)
        root.addView(button("वर्तमान लक्ष्य के लिए Workflow Step जोड़ें") { addStep() })
        root.addView(button("पूरा Workflow इस ऐप में सहेजें") { saveWorkflow() })
        root.addView(button("सहेजे गए ऐप प्रोफ़ाइल देखें") { showProfiles() })
        status = TextView(this).apply { setTextColor(0xFFE1E7F5.toInt()); textSize = 13f; setPadding(12, 18, 12, 18); text = "पहले ऐप खोलें और Accessibility अनुमति चालू करें।" }
        root.addView(status)
        root.addView(button("वापस") { finish() })
        val outer = ScrollView(this); outer.addView(root); setContentView(outer)
    }

    private fun captureApp() {
        val snapshot = ShraddhaAccessibilityService.captureVisibleScreen()
        if (snapshot == null) { report("स्क्रीन उपलब्ध नहीं। Accessibility सेवा चालू करें।"); return }
        currentPackage = snapshot.packageName
        appLabel.setText(currentPackage)
        report("ऐप: $currentPackage\nस्क्रीन तत्व: ${snapshot.elements.size}\n\n" + snapshot.elements.take(45).joinToString("\n") { e -> "${if (e.clickable) "[BUTTON]" else if (e.editable) "[FIELD]" else "[TEXT]"} ${e.text.ifBlank { e.description.ifBlank { e.hintText } }.take(100)} • ${e.id}" })
    }

    private fun saveAnchor() {
        val snapshot = ShraddhaAccessibilityService.captureVisibleScreen() ?: return report("पहले वर्तमान स्क्रीन पहचानें।")
        currentPackage = snapshot.packageName
        val label = target.text.toString().trim(); if (label.isBlank()) return report("UI लक्ष्य का नाम लिखें।")
        val item = snapshot.elements.filter { it.text.equals(label, true) || it.description.equals(label, true) || it.hintText.equals(label, true) }.maxByOrNull { e -> (if (e.clickable) 2 else 0) + (if (e.editable) 2 else 0) } ?: return report("यह नाम वर्तमान स्क्रीन पर नहीं मिला।")
        val selectedRole = role.selectedItem.toString()
        val stableResourceId = item.id.takeIf { it.contains(":id/") }.orEmpty()
        val anchor = SemanticAnchor(selectedRole, label, stableResourceId, item.description, item.className, packageName = snapshot.packageName, confidence = 1f)
        val saved = runCatching { store.upsertAnchor(snapshot.packageName, appLabel.text.toString().ifBlank { snapshot.packageName }, anchor); true }.getOrDefault(false)
        report(if (saved) "सीखा गया: $selectedRole • $label\nResource ID: ${stableResourceId.ifBlank { "semantic text/hint" }}" else "प्रोफ़ाइल सेव नहीं हो सकी।")
    }

    private fun addStep() {
        val type = action.selectedItem.toString(); val label = target.text.toString().trim()
        val key = inputKey.text.toString().trim()
        val expectedText = expected.text.toString().trim()
        if (type in listOf("CLICK", "TYPE") && label.isBlank()) return report("पहले target label लिखें और उसे anchor के रूप में सहेजें।")
        if (type == "SELECT_FILE" && key.isBlank()) return report("फ़ाइल का सटीक नाम/variable key दें, जैसे Rahul.jpg।")
        if (type == "TYPE" && key.isBlank()) return report("TYPE चरण के लिए variable key आवश्यक है; वास्तविक संवेदनशील डेटा सेव न करें।")
        val safety = OperatorSafetyPolicy.assess(type, label, key)
        if (safety.risk == OperatorSafetyPolicy.Risk.BLOCK) return report(safety.reason)
        val targetRole = if (type == "TYPE") "field" else role.selectedItem.toString()
        pending.add(LearnedStep(action = type, targetRole = targetRole, targetLabel = label, inputTemplate = key, expectedText = expectedText, destructive = safety.risk == OperatorSafetyPolicy.Risk.CONFIRM))
        report("Step जोड़ा गया: ${pending.size}\n${pending.joinToString("\n") { "${it.action} → ${it.targetLabel.ifBlank { "(none)" }}" }}")
    }

    private fun saveWorkflow() {
        val snapshot = ShraddhaAccessibilityService.captureVisibleScreen() ?: return report("पहले प्रशिक्षित ऐप खोलें।")
        currentPackage = snapshot.packageName
        val name = workflow.text.toString().trim(); if (name.isBlank() || pending.isEmpty()) return report("Workflow नाम और कम से कम एक step आवश्यक है।")
        val ok = runCatching { store.saveWorkflow(currentPackage, appLabel.text.toString().ifBlank { currentPackage }, name, pending.toList()); true }.getOrDefault(false)
        if (ok) { val count = pending.size; pending.clear(); report("'$name' सेव हुआ। $count चरण।\nअब रन करते समय हर स्क्रीन पर लक्ष्य दोबारा खोजा जाएगा।") } else report("Workflow सेव नहीं हुआ।")
    }

    private fun showProfiles() { report(store.all().joinToString("\n\n") { "${it.displayName} (${it.packageName})\nAnchors: ${it.anchors.size}\nWorkflows: ${it.workflows.keys.joinToString()}" }.ifBlank { "अभी कोई ऐप प्रोफ़ाइल नहीं है।" }) }
    private fun report(text: String) { status.text = text }
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; setHintTextColor(0xFF8894AE.toInt()); setTextColor(Color.WHITE); textSize = 14f; setPadding(14, 12, 14, 12); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF18243A.toInt()); cornerRadius = 16f; setStroke(1, 0xFF405477.toInt()) }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 } }
    private fun spinner(items: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@AppTrainingActivity, android.R.layout.simple_spinner_dropdown_item, items); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 } }
    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF5946A8.toInt()); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 } }
}

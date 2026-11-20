package `in`.shraddha.ai.operator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import `in`.shraddha.ai.operator.learning.AppProfileStore
import `in`.shraddha.ai.operator.learning.AppTrainingActivity
import `in`.shraddha.ai.operator.learning.AppTrainingProfile
import `in`.shraddha.ai.operator.learning.AdaptiveWorkflowRunner
import `in`.shraddha.ai.operator.learning.NaturalLanguageWorkflowRouter
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/** Operator console: exposes real Android accessibility actions and live observation feedback. */
class OperatorActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var appName: EditText
    private lateinit var target: EditText
    private lateinit var typed: EditText
    private lateinit var commandInput: EditText
    @Volatile private var activeRunner: AdaptiveWorkflowRunner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(9, 13, 25)) }
        root.addView(TextView(this).apply { text = "SHRADDHA • LIVE APP OPERATOR"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 8, 0, 18) })
        root.addView(TextView(this).apply { text = "वास्तविक स्क्रीन निरीक्षण • कार्रवाई • परिणाम सत्यापन"; textSize = 12f; setTextColor(0xFFB9A9FF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, 18) })
        appName = field("ऐप का नाम (जैसे Chrome)")
        target = field("स्क्रीन पर दिखने वाला बटन/टेक्स्ट")
        typed = field("फोकस किए गए सामान्य टेक्स्ट फील्ड में लिखें")
        root.addView(appName); root.addView(target); root.addView(typed)
        commandInput = field("श्रद्धा को निर्देश दें: जैसे Rahul.jpg upload करो")
        root.addView(commandInput)
        root.addView(action("🎙️ बोलकर निर्देश दें") { captureVoiceCommand() })
        root.addView(action("प्राकृतिक भाषा निर्देश चलाएँ") { routeNaturalCommand() })
        root.addView(action("स्क्रीन देखें / Refresh") { runOperator { it.observe() } })
        root.addView(action("App Training / सीखने की लैब") { startActivity(android.content.Intent(this, AppTrainingActivity::class.java)) })
        root.addView(action("सीखे हुए Workflow चलाएँ") { chooseLearnedWorkflow() })
        root.addView(action("■  चल रहा Workflow रोकें") { activeRunner?.stop() ?: run { log.text = "अभी कोई Workflow सक्रिय नहीं है।" } })
        root.addView(action("फ़ाइल Access / फ़ोल्डर अनुमति") { startActivity(android.content.Intent(this, `in`.shraddha.ai.operator.files.FileAccessActivity::class.java)) })
        root.addView(action("ऐप खोलें") { runOperatorStep(OperatorStep(OperatorActionType.OPEN_APP, target = appName.text.toString())) })
        root.addView(action("दिख रहे टेक्स्ट पर क्लिक करें") { runOperatorStep(OperatorStep(OperatorActionType.CLICK_TEXT, target = target.text.toString())) })
        root.addView(action("फोकस किए गए फील्ड में टाइप करें") { runOperatorStep(OperatorStep(OperatorActionType.TYPE_TEXT, value = typed.text.toString())) })
        root.addView(action("Back / Home / Scroll") {
            val choices = arrayOf("Back", "Home", "Scroll forward")
            android.app.AlertDialog.Builder(this).setTitle("कार्रवाई चुनें").setItems(choices) { _, which ->
                val type = when (which) { 0 -> OperatorActionType.BACK; 1 -> OperatorActionType.HOME; else -> OperatorActionType.SCROLL_FORWARD }
                runOperatorStep(OperatorStep(type))
            }.show()
        })
        log = TextView(this).apply { text = "स्थिति: Accessibility permission चालू करें।\nसंवेदनशील/पासवर्ड स्क्रीन पर कार्रवाई रोकी जाएगी।"; textSize = 13f; setTextColor(0xFFE0E6F5.toInt()); setPadding(14, 18, 14, 18) }
        val scroll = ScrollView(this); scroll.addView(log); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(action("← वापस") { finish() })
        setContentView(root)
    }




    private fun captureVoiceCommand() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "श्रद्धा को काम बताइए")
        }
        runCatching { startActivityForResult(intent, 902) }.onFailure { log.text = "इस डिवाइस पर Speech Recognition उपलब्ध नहीं है।" }
    }

    override fun onDestroy() {
        activeRunner?.stop()
        activeRunner = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 902 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) { commandInput.setText(spoken); routeNaturalCommand() }
        }
    }

    private fun routeNaturalCommand() {
        val snapshot = `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService.captureVisibleScreen()
        if (snapshot == null) { log.text = "Accessibility सेवा चालू करें और target app खोलें।"; return }
        val profile = AppProfileStore(this).get(snapshot.packageName)
        if (profile == null) { log.text = "इस ऐप (${snapshot.packageName}) की training profile नहीं मिली। पहले App Training करें।"; return }
        val result = NaturalLanguageWorkflowRouter.route(commandInput.text.toString(), profile)
        val route = result.route
        if (route == null) { log.text = result.message; return }
        val steps = route.profile.workflows[route.workflowName].orEmpty()
        val policyDecisions = steps.map { OperatorSafetyPolicy.assess(it.action, it.targetLabel, it.inputTemplate) }
        if (policyDecisions.any { it.risk == OperatorSafetyPolicy.Risk.BLOCK }) {
            log.text = "यह workflow सुरक्षा नीति से block हुआ: ${policyDecisions.first { it.risk == OperatorSafetyPolicy.Risk.BLOCK }.reason}"
            return
        }
        val variables = EditText(this).apply { hint = "अन्य मान: customer_name=Rahul, phone=..."; setTextColor(Color.WHITE); setHintTextColor(0xFF8290AA.toInt()) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0); addView(variables) }
        android.app.AlertDialog.Builder(this).setTitle("सीखा हुआ काम चलाएँ")
            .setMessage("${result.message}\nविश्वास स्कोर: ${(route.confidence * 100).toInt()}%\n${route.note}\n\nज़रूरी जानकारी भरें। हर step के बाद परिणाम जाँचा जाएगा।")
            .setView(panel).setNegativeButton("रद्द", null)
            .setPositiveButton("चरण-दर-चरण चलाएँ") { _, _ ->
                val extra = variables.text.toString().split(",").mapNotNull { part -> val pair = part.trim().split("=", limit = 2); if (pair.size == 2) pair[0].trim() to pair[1].trim() else null }.toMap()
                val allValues = route.values + extra
                // Sensitive actions are confirmed individually immediately before execution.
                runLearned(route.profile, route.workflowName, allValues)
            }.show()
    }

    private fun chooseLearnedWorkflow() {
        val profiles = AppProfileStore(this).all()
        if (profiles.isEmpty()) { log.text = "पहले App Training में ऐप प्रोफ़ाइल और workflow सहेजें।"; return }
        val labels = profiles.flatMap { p -> p.workflows.keys.map { "${p.displayName} • $it" } }
        android.app.AlertDialog.Builder(this).setTitle("सीखा हुआ Workflow चुनें").setItems(labels.toTypedArray()) { _, which ->
            val profile = profiles.first { p -> p.workflows.keys.any { "${p.displayName} • $it" == labels[which] } }
            val name = labels[which].substringAfter(" • ")
            val steps = profile.workflows[name].orEmpty()
            val vars = EditText(this).apply { hint = "Variable values: customer_name=Rahul, phone=..."; setTextColor(Color.WHITE); setHintTextColor(0xFF8290AA.toInt()) }
            val container = LinearLayout(this).apply { setPadding(24, 12, 24, 0); addView(vars) }
            android.app.AlertDialog.Builder(this).setTitle("Workflow: $name").setMessage("ऐप: ${profile.displayName}\nचरण: ${steps.size}\nहर चरण पर स्क्रीन दोबारा पढ़ी जाएगी। संवेदनशील/पासवर्ड फ़ील्ड ब्लॉक रहेंगे।")
                .setView(container).setNegativeButton("रद्द") { _, _ -> }.setPositiveButton("चलाएँ") { _, _ ->
                    val map = vars.text.toString().split(",").mapNotNull { part -> val pair = part.trim().split("=", limit = 2); if (pair.size == 2) pair[0].trim() to pair[1].trim() else null }.toMap()
                    val decisions = steps.map { OperatorSafetyPolicy.assess(it.action, it.targetLabel, it.inputTemplate) }
            if (decisions.any { it.risk == OperatorSafetyPolicy.Risk.BLOCK }) {
                log.text = "यह workflow सुरक्षा नीति से block हुआ: ${decisions.first { it.risk == OperatorSafetyPolicy.Risk.BLOCK }.reason}"
                return@setPositiveButton
            }
            // Sensitive actions are confirmed individually immediately before execution.
                    runLearned(profile, name, map)
                }.show()
        }.show()
    }

    private fun runOperatorStep(step: OperatorStep) {
        val decision = OperatorSafetyPolicy.assess(step.action.name, step.target, step.value)
        if (decision.risk == OperatorSafetyPolicy.Risk.BLOCK) {
            log.text = "BLOCKED: ${decision.reason}\\n\\n" + log.text.take(3000)
            return
        }
        if (step.requiresConfirmation || decision.risk == OperatorSafetyPolicy.Risk.CONFIRM) {
            android.app.AlertDialog.Builder(this)
                .setTitle("संवेदनशील कार्रवाई की पुष्टि")
                .setMessage(decision.reason + "\\n\\nक्या आप इस चरण को चलाना चाहते हैं?")
                .setNegativeButton("रद्द", null)
                .setPositiveButton("हाँ, चलाएँ") { _, _ -> runOperator { it.execute(step, confirmed = true) } }
                .show()
        } else {
            runOperator { it.execute(step, confirmed = false) }
        }
    }

    private fun runLearned(
        profile: AppTrainingProfile,
        name: String,
        values: Map<String, String>,
        startAt: Int = 0,
        confirmedStepIndex: Int? = null
    ) {
        Thread {
            val runner = AdaptiveWorkflowRunner(this) { event -> runOnUiThread {
                val snapshot = event.snapshot?.let { "\nऐप: ${it.packageName} • UI: ${it.elements.size} तत्व" }.orEmpty()
                log.text = "${event.state}: ${event.message}$snapshot\n\n" + log.text.take(4000)
                val pendingStep = event.stepIndex
                if (event.state == OperatorTaskState.WAITING_CONFIRMATION && pendingStep != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("इस चरण की अनुमति")
                        .setMessage(event.message + "\n\nक्या केवल इसी चरण को चलाने की अनुमति देते हैं?")
                        .setNegativeButton("नहीं, रोकें") { _, _ -> activeRunner?.stop(); activeRunner = null }
                        .setPositiveButton("हाँ, यह चरण चलाएँ") { _, _ ->
                            runLearned(profile, name, values, startAt = pendingStep, confirmedStepIndex = pendingStep)
                        }
                        .show()
                }
                if (event.state in setOf(OperatorTaskState.COMPLETED, OperatorTaskState.FAILED, OperatorTaskState.CANCELLED)) activeRunner = null
            } }
            activeRunner = runner
            runner.run(profile, name, values, startAt, confirmedStepIndex)
        }.start()
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; setHintTextColor(0xFF8290AA.toInt()); setTextColor(Color.WHITE); textSize = 14f
        setSingleLine(true); setPadding(14, 10, 14, 10)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF172238.toInt()); cornerRadius = 18f; setStroke(1, 0xFF354565.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
    }
    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF44377D.toInt())
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
    }
    private fun runOperator(work: (OperatorEngine) -> Unit) {
        Thread {
            val engine = OperatorEngine { event -> runOnUiThread {
                val detail = event.snapshot?.let { "\nApp: ${it.packageName}\nElements: ${it.elements.size}\n" + it.elements.take(35).joinToString("\n") { e -> "• ${(e.text.ifBlank { e.description }).take(90)} ${if (e.clickable) "[click]" else ""}${if (e.editable && !e.password) "[input]" else ""}" } }.orEmpty()
                log.text = "${event.state}: ${event.message}$detail\n\n" + log.text.take(5000)
            } }
            work(engine)
        }.start()
    }
}

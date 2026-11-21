package `in`.shraddha.ai.operator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import `in`.shraddha.ai.operator.learning.AppProfileStore
import `in`.shraddha.ai.operator.learning.AppTrainingActivity
import `in`.shraddha.ai.operator.learning.AppTrainingProfile
import `in`.shraddha.ai.operator.learning.AdaptiveWorkflowRunner
import `in`.shraddha.ai.operator.learning.NaturalLanguageWorkflowRouter
import `in`.shraddha.ai.future.Risk
import `in`.shraddha.ai.future.LocalAuditTrail
import `in`.shraddha.ai.future.ShraddhaIntegrationHub
import `in`.shraddha.ai.future.WorkflowStatus

/** Operator console: exposes real Android accessibility actions and live observation feedback. */
class OperatorActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var appName: EditText
    private lateinit var target: EditText
    private lateinit var typed: EditText
    private lateinit var commandInput: EditText

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
        root.addView(action("Task Planner → Verified Operator") { runPlannedOperator() })
        root.addView(action("स्क्रीन देखें / Refresh") { runOperator { it.observe() } })
        root.addView(action("फॉर्म इमेज → AI Autofill Review") { startActivity(android.content.Intent(this, FormAutofillActivity::class.java)) })
        root.addView(action("App Training / सीखने की लैब") { startActivity(android.content.Intent(this, AppTrainingActivity::class.java)) })
        root.addView(action("सीखे हुए Workflow चलाएँ") { chooseLearnedWorkflow() })
        root.addView(action("फ़ाइल Access / फ़ोल्डर अनुमति") { startActivity(android.content.Intent(this, `in`.shraddha.ai.operator.files.FileAccessActivity::class.java)) })
        root.addView(action("ऐप खोलें") { runOperator { it.execute(OperatorStep(OperatorActionType.OPEN_APP, target = appName.text.toString())) } })
        root.addView(action("दिख रहे टेक्स्ट पर क्लिक करें") { runOperator { it.execute(OperatorStep(OperatorActionType.CLICK_TEXT, target = target.text.toString())) } })
        root.addView(action("फोकस किए गए फील्ड में टाइप करें") { runOperator { it.execute(OperatorStep(OperatorActionType.TYPE_TEXT, value = typed.text.toString())) } })
        root.addView(action("Back / Home / Scroll") {
            val choices = arrayOf("Back", "Home", "Scroll forward")
            android.app.AlertDialog.Builder(this).setTitle("कार्रवाई चुनें").setItems(choices) { _, which ->
                val type = when (which) { 0 -> OperatorActionType.BACK; 1 -> OperatorActionType.HOME; else -> OperatorActionType.SCROLL_FORWARD }
                runOperator { it.execute(OperatorStep(type)) }
            }.show()
        })
        log = TextView(this).apply { text = "स्थिति: Accessibility permission चालू करें।\nसंवेदनशील/पासवर्ड स्क्रीन पर कार्रवाई रोकी जाएगी।"; textSize = 13f; setTextColor(0xFFE0E6F5.toInt()); setPadding(14, 18, 14, 18) }
        val scroll = ScrollView(this); scroll.addView(log); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(action("← वापस") { finish() })
        setContentView(root)
        handleCouncilOperatorPlan(intent)
    }





    /** Accepts a validated Council proposal only after a human reviews the complete action list. */
    private fun handleCouncilOperatorPlan(source: android.content.Intent?) {
        val raw = source?.getStringExtra("council_operator_plan") ?: return
        val plan = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            log.text = "Council plan JSON अमान्य है। कोई action नहीं चलाया गया।"; return
        }
        val array = plan.optJSONArray("steps") ?: JSONArray()
        if (array.length() !in 1..12 || plan.optString("mode") != "council_operator_plan_proposal") {
            log.text = "Council plan में सुरक्षित, supported steps नहीं हैं।"; return
        }
        val planned = mutableListOf<PlannedOperatorAction>()
        var previous: String? = null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val title = item.optString("title")
            val action = runCatching { OperatorActionType.valueOf(item.optString("action")) }.getOrNull() ?: continue
            val target = item.optString("target")
            val value = item.optString("value")
            val expected = item.optString("expectedVisibleText")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,48}")) || title.isBlank() || title.length > 100) continue
            if (target.length > 120 || value.length > 500 || expected.length > 120) continue
            if (action == OperatorActionType.OPEN_APP && target.isBlank()) continue
            if (action == OperatorActionType.CLICK_TEXT && target.isBlank()) continue
            if (action == OperatorActionType.TYPE_TEXT && (value.isBlank() || target.isNotBlank())) continue
            if (action !in setOf(OperatorActionType.OPEN_APP, OperatorActionType.CLICK_TEXT, OperatorActionType.TYPE_TEXT) && (target.isNotBlank() || value.isNotBlank())) continue
            val risk = if (action in setOf(OperatorActionType.OPEN_APP, OperatorActionType.BACK, OperatorActionType.HOME, OperatorActionType.SCROLL_FORWARD)) Risk.READ_ONLY else Risk.SENSITIVE
            planned += PlannedOperatorAction(
                id, title, OperatorStep(action, target, value, expected, requiresConfirmation = true),
                dependsOn = previous?.let { setOf(it) } ?: emptySet(),
                risk = risk, requiresApproval = true
            )
            previous = id
        }
        if (planned.size != array.length()) {
            log.text = "Council plan में unsupported या invalid step मिला। सुरक्षा के लिए पूरा plan reject किया गया।"; return
        }
        val summary = planned.mapIndexed { i, action -> "${i + 1}. ${action.title} [${action.step.action}]" }.joinToString("\n")
        android.app.AlertDialog.Builder(this)
            .setTitle("AI Council का प्रस्तावित Operator plan")
            .setMessage("Goal: ${plan.optString("goal")}\n\n$summary\n\nयह AI का प्रस्ताव है, verified instruction नहीं। हर step चलाने से पहले अलग अनुमति माँगी जाएगी। किसी step को अस्वीकार करने पर workflow रुक जाएगा।")
            .setNegativeButton("रद्द करें") { _, _ -> log.text = "Council plan रद्द। कोई action execute नहीं हुआ।" }
            .setPositiveButton("Plan स्वीकार करें") { _, _ -> runCouncilPlan(planned, plan.optString("goal")) }
            .show()
    }

    private fun runCouncilPlan(planned: List<PlannedOperatorAction>, goal: String) {
        Thread {
            val engine = OperatorEngine(
                emit = { event -> runOnUiThread {
                    val detail = event.snapshot?.let { "\\nApp: ${it.packageName} • UI: ${it.elements.size}" }.orEmpty()
                    log.text = "${event.state}: ${event.message}$detail\\n\\n" + log.text.take(5000)
                } },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) }
            )
            val bridge = TaskPlannerOperatorBridge(
                executeVerified = { step, confirmed -> engine.execute(step, confirmed) },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append("council_${event}", outcome, detail) }
            )
            val workflowId = "council-" + java.util.UUID.randomUUID().toString().take(8)
            bridge.createPlan(workflowId, goal.take(120).ifBlank { "AI Council Operator plan" }, planned)
            val session = bridge.start()
            runOnUiThread { showPlannedSession(bridge, session) }
        }.start()
    }

    /** Builds an explicit plan from the operator fields; every mutating step is approved separately. */
    private fun runPlannedOperator() {
        val planned = mutableListOf<PlannedOperatorAction>()
        var previous: String? = null
        val app = appName.text.toString().trim()
        val label = target.text.toString().trim()
        val value = typed.text.toString()
        if (app.isNotBlank()) {
            val id = "open_app"
            planned += PlannedOperatorAction(id, "Open $app", OperatorStep(OperatorActionType.OPEN_APP, target = app),
                dependsOn = previous?.let { setOf(it) } ?: emptySet(), risk = Risk.READ_ONLY)
            previous = id
        }
        if (label.isNotBlank()) {
            val id = "click_target"
            planned += PlannedOperatorAction(id, "Click $label", OperatorStep(OperatorActionType.CLICK_TEXT, target = label, requiresConfirmation = true),
                dependsOn = previous?.let { setOf(it) } ?: emptySet(), risk = Risk.SENSITIVE, requiresApproval = true)
            previous = id
        }
        if (value.isNotBlank()) {
            val id = "type_value"
            planned += PlannedOperatorAction(id, "Enter supplied text", OperatorStep(OperatorActionType.TYPE_TEXT, value = value, requiresConfirmation = true),
                dependsOn = previous?.let { setOf(it) } ?: emptySet(), risk = Risk.SENSITIVE, requiresApproval = true)
        }
        if (planned.isEmpty()) { log.text = "पहले ऐप का नाम, target text या सामान्य text भरें।"; return }
        Thread {
            val engine = OperatorEngine(
                emit = { event -> runOnUiThread {
                    val detail = event.snapshot?.let { "\nApp: ${it.packageName} • UI: ${it.elements.size}" }.orEmpty()
                    log.text = "${event.state}: ${event.message}$detail\n\n" + log.text.take(5000)
                } },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) }
            )
            val bridge = TaskPlannerOperatorBridge(
                executeVerified = { step, confirmed -> engine.execute(step, confirmed) },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) }
            )
            val id = "operator-" + java.util.UUID.randomUUID().toString().take(8)
            bridge.createPlan(id, "Manual Operator Task", planned)
            val session = bridge.start()
            runOnUiThread { showPlannedSession(bridge, session) }
        }.start()
    }

    private fun showPlannedSession(bridge: TaskPlannerOperatorBridge, session: ShraddhaIntegrationHub.Session) {
        val step = session.workflow.steps.getOrNull(session.workflow.currentStep)
        log.text = "Planner: ${session.workflow.status}\n${session.workflow.message}\n" + log.text.take(4500)
        if (session.workflow.status == WorkflowStatus.WAITING_FOR_APPROVAL && step != null) {
            android.app.AlertDialog.Builder(this).setTitle("इस Planner चरण की अनुमति")
                .setMessage("कार्य: ${step.title}\n\nयह अनुमति केवल इसी चरण के लिए है। मना करने पर अगला action नहीं चलेगा।")
                .setNegativeButton("मना करें") { _, _ -> Thread { val result = bridge.approveCurrentStep(false); runOnUiThread { showPlannedSession(bridge, result) } }.start() }
                .setPositiveButton("हाँ, यह चरण चलाएँ") { _, _ -> Thread { val result = bridge.approveCurrentStep(true); runOnUiThread { showPlannedSession(bridge, result) } }.start() }
                .show()
        }
    }
    private fun captureVoiceCommand() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "श्रद्धा को काम बताइए")
        }
        runCatching { startActivityForResult(intent, 902) }.onFailure { log.text = "इस डिवाइस पर Speech Recognition उपलब्ध नहीं है।" }
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
        val variables = EditText(this).apply { hint = "अन्य मान: customer_name=Rahul, phone=..."; setTextColor(Color.WHITE); setHintTextColor(0xFF8290AA.toInt()) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0); addView(variables) }
        android.app.AlertDialog.Builder(this).setTitle("सीखा हुआ काम चलाएँ")
            .setMessage("${result.message}\nविश्वास स्कोर: ${(route.confidence * 100).toInt()}%\n${route.note}\n\nज़रूरी जानकारी भरें। हर step के बाद परिणाम जाँचा जाएगा। जोखिम वाले चरण पर अलग से अनुमति माँगी जाएगी।")
            .setView(panel).setNegativeButton("रद्द", null)
            .setPositiveButton("चलाएँ") { _, _ ->
                val extra = variables.text.toString().split(",").mapNotNull { part -> val pair = part.trim().split("=", limit = 2); if (pair.size == 2) pair[0].trim() to pair[1].trim() else null }.toMap()
                runLearned(route.profile, route.workflowName, route.values + extra)
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
            val checkpoint = AdaptiveWorkflowRunner(this) { }.resumableCheckpoint(profile, name)
            val dialog = android.app.AlertDialog.Builder(this).setTitle("Workflow: $name")
                .setMessage("ऐप: ${profile.displayName}\nचरण: ${steps.size}\nहर चरण पर स्क्रीन दोबारा पढ़ी जाएगी। संवेदनशील/पासवर्ड फ़ील्ड ब्लॉक रहेंगे।" +
                    if (checkpoint != null) "\n\nसुरक्षित checkpoint: चरण ${checkpoint.nextStep + 1}/${checkpoint.totalSteps} से आगे बढ़ सकते हैं।" else "")
                .setView(container)
                .setNegativeButton("रद्द", null)
                .setPositiveButton("शुरू से चलाएँ") { _, _ ->
                    val map = vars.text.toString().split(",").mapNotNull { part -> val pair = part.trim().split("=", limit = 2); if (pair.size == 2) pair[0].trim() to pair[1].trim() else null }.toMap()
                    runLearned(profile, name, map, resume = false)
                }
                .setNeutralButton("Checkpoint से Resume") { _, _ ->
                    val map = vars.text.toString().split(",").mapNotNull { part -> val pair = part.trim().split("=", limit = 2); if (pair.size == 2) pair[0].trim() to pair[1].trim() else null }.toMap()
                    runLearned(profile, name, map, resume = true)
                }.create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).isEnabled = checkpoint != null
            }
            dialog.show()
        }.show()
    }

    private fun runLearned(profile: AppTrainingProfile, name: String, values: Map<String, String>, resume: Boolean = false) {
        lateinit var runner: AdaptiveWorkflowRunner
        runner = AdaptiveWorkflowRunner(this) { event -> runOnUiThread {
            val snapshot = event.snapshot?.let { "\nऐप: ${it.packageName} • UI: ${it.elements.size} तत्व" }.orEmpty()
            log.text = "${event.state}: ${event.message}$snapshot\n\n" + log.text.take(4000)
            if (event.state == OperatorTaskState.WAITING_CONFIRMATION) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("इस चरण की अनुमति")
                    .setMessage("${event.message}\n\nयह अनुमति केवल इसी एक चरण के लिए होगी। अगले जोखिम वाले चरण पर फिर पूछा जाएगा।")
                    .setNegativeButton("नहीं", null)
                    .setPositiveButton("हाँ, केवल यह चरण") { _, _ ->
                        Thread { runner.run(profile, name, values, confirmed = true, resume = true) }.start()
                    }.show()
            }
        } }
        Thread { runner.run(profile, name, values, confirmed = false, resume = resume) }.start()
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
            val engine = OperatorEngine(
                emit = { event -> runOnUiThread {
                val detail = event.snapshot?.let { "\nApp: ${it.packageName}\nElements: ${it.elements.size}\n" + it.elements.take(35).joinToString("\n") { e -> "• ${(e.text.ifBlank { e.description }).take(90)} ${if (e.clickable) "[click]" else ""}${if (e.editable && !e.password) "[input]" else ""}" } }.orEmpty()
                log.text = "${event.state}: ${event.message}$detail\n\n" + log.text.take(5000)
            } },
                audit = { event, outcome, detail ->
                    `in`.shraddha.ai.future.LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail)
                }
            )
            work(engine)
        }.start()
    }
}

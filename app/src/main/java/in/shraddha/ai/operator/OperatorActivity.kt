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
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceGatewaySettings

/** Operator console: exposes real Android accessibility actions and live observation feedback. */
class OperatorActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var appName: EditText
    private lateinit var target: EditText
    private lateinit var typed: EditText
    private lateinit var typedTarget: EditText
    private lateinit var commandInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantName = NovaPreferences.getString(
            this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME
        ).trim().ifBlank { AssistantBrand.NAME }

        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        fun card(title: String, subtitle: String = ""): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(20, 38, 57), Color.rgb(11, 23, 38))
                ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF294B62.toInt()) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) }
                addView(TextView(this@OperatorActivity).apply {
                    text = title; textSize = 15f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                if (subtitle.isNotBlank()) addView(TextView(this@OperatorActivity).apply {
                    text = subtitle; textSize = 11f; setTextColor(0xFF8DB5C8.toInt())
                    setPadding(0, dp(4), 0, dp(11))
                })
            }
        }
        fun chip(label: String, primary: Boolean = false, click: () -> Unit): Button =
            Button(this).apply {
                text = label; isAllCaps = false; textSize = 12f
                setTextColor(if (primary) 0xFF06141D.toInt() else 0xFFDDF8FF.toInt())
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    if (primary) intArrayOf(0xFF65E5E8.toInt(), 0xFF22AFC4.toInt())
                    else intArrayOf(0xFF1B354B.toInt(), 0xFF13283B.toInt())
                ).apply {
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), if (primary) 0xFF9AF8F3.toInt() else 0xFF345A70.toInt())
                }
                elevation = dp(3).toFloat()
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener { click() }
            }
        fun row(parent: LinearLayout, first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(chip(first.first, true, first.second), LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
            line.addView(chip(second.first, false, second.second), LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        if (intent.hasExtra("mobile_voice_command")) {
            showCompactMobileCommandMode(assistantName)
            return
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF050B14.toInt(), 0xFF0A1726.toInt(), 0xFF07111D.toInt())
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(17))
        }
        val emblem = TextView(this).apply {
            text = "✦"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF39D4E0.toInt(), 0xFF315C9B.toInt())
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) }
        }
        header.addView(emblem)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OperatorActivity).apply {
                text = "$assistantName Operator"; textSize = 19f; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@OperatorActivity).apply {
                text = "LIVE APP CONTROL CENTER"; textSize = 10f; setTextColor(0xFF62DCE7.toInt())
                letterSpacing = 0.12f
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "● READY"; textSize = 10f; setTextColor(0xFF70E6B2.toInt())
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x2226C88A); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x6656D9A3)
            }
        })
        page.addView(header)

        val commandCard = card("COMMAND DECK", "बोलकर या लिखकर श्रद्धा को काम बताइए")
        commandInput = field("उदाहरण: Chrome खोलो और वेबसाइट खोजो")
        commandInput.setSingleLine(false); commandInput.maxLines = 3
        intent.getStringExtra("mobile_voice_command")?.takeIf { it.isNotBlank() }?.let { commandInput.setText(it) }
        commandCard.addView(commandInput)
        row(commandCard,
            "🎙 बोलकर बताएं" to { captureVoiceCommand() },
            "निर्देश समझें" to { routeNaturalCommand() })
        row(commandCard,
            "Task Planner चलाएँ" to { runPlannedOperator() },
            "स्क्रीन Refresh" to { runOperator { it.observe() } })
        page.addView(commandCard)

        val controls = card("LIVE SCREEN CONTROLS", "पहले target app खोलें और Shraddha AI की Accessibility अनुमति चालू रखें")
        appName = field("ऐप का नाम — जैसे Chrome")
        target = field("स्क्रीन पर क्लिक करने वाले बटन का सटीक नाम")
        typedTarget = field("जिस टेक्स्ट फील्ड में लिखना है उसका label / hint")
        typed = field("वह टेक्स्ट जो फील्ड में भरना है")
        controls.addView(appName); controls.addView(target); controls.addView(typedTarget); controls.addView(typed)
        row(controls,
            "ऐप खोलें" to { runOperatorStep(OperatorStep(OperatorActionType.OPEN_APP, target = appName.text.toString())) },
            "दिख रहे बटन पर क्लिक" to { runOperatorStep(OperatorStep(OperatorActionType.CLICK_TEXT, target = target.text.toString(), requiresConfirmation = true)) })
        row(controls,
            "पहचाने गए फील्ड में लिखें" to {
                runOperatorStep(OperatorStep(
                    OperatorActionType.TYPE_TEXT,
                    target = typedTarget.text.toString(),
                    value = typed.text.toString(),
                    requiresConfirmation = true
                ))
            },
            "Back / Home / Scroll" to {
                val choices = arrayOf("Back", "Home", "Scroll forward")
                android.app.AlertDialog.Builder(this).setTitle("नेविगेशन कार्रवाई चुनें").setItems(choices) { _, which ->
                    val type = when (which) { 0 -> OperatorActionType.BACK; 1 -> OperatorActionType.HOME; else -> OperatorActionType.SCROLL_FORWARD }
                    runOperatorStep(OperatorStep(type))
                }.show()
            })
        page.addView(controls)

        val toolsCard = card("AUTOMATION TOOLS", "सीखे हुए ऐप, फ़ॉर्म और फ़ाइल वर्कफ़्लो")
        row(toolsCard,
            "सीखा Workflow चलाएँ" to { chooseLearnedWorkflow() },
            "App Training Lab" to { startActivity(android.content.Intent(this, AppTrainingActivity::class.java)) })
        row(toolsCard,
            "AKKPS Runner Data Entry" to { startActivity(android.content.Intent(this, `in`.shraddha.ai.operator.website.AkkpsRunnerActivity::class.java)) },
            "AKKPS वेबसाइट पर Login" to { startActivity(android.content.Intent(this, `in`.shraddha.ai.operator.website.AkkpsRunnerActivity::class.java)) })
        row(toolsCard,
            "AI Form Autofill" to { startActivity(android.content.Intent(this, FormAutofillActivity::class.java)) },
            "File Access" to { startActivity(android.content.Intent(this, `in`.shraddha.ai.operator.files.FileAccessActivity::class.java)) })
        toolsCard.addView(chip("SMS / WhatsApp • Voice Message", true) {
            startActivity(android.content.Intent(this, com.Lia.assistant.action.MessageComposeActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(toolsCard)

        val recoveryCard = card("SAFETY & RECOVERY", "हर संवेदनशील कार्रवाई पर पुष्टि • कार्रवाई के बाद परिणाम जाँच")
        row(recoveryCard,
            "Interrupted Action जाँचें" to { reviewInterruptedOperatorActions() },
            "Voice Action Journal" to { reviewInterruptedVoiceAction() })
        log = TextView(this).apply {
            text = "स्थिति: Accessibility permission चालू करें।\nसंवेदनशील/पासवर्ड स्क्रीन पर कार्रवाई रोकी जाएगी।"
            textSize = 12f; setTextColor(0xFFD9EAF2.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12))
            movementMethod = android.text.method.ScrollingMovementMethod()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091522.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF31566B.toInt())
            }
        }
        recoveryCard.addView(log, LinearLayout.LayoutParams(-1, dp(145)))
        recoveryCard.addView(chip("← वापस", false) { finish() }, LinearLayout.LayoutParams(-1, dp(46)))
        page.addView(recoveryCard)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setPadding(0, 0, 0, dp(8)); addView(page) }
        setContentView(scroll)
        handleCouncilOperatorPlan(intent)
    }



    /** Shows restart-recovery evidence and asks before scheduling any remaining steps. */
    private fun reviewInterruptedOperatorActions() {
        Thread {
            val reviewBundle = runCatching {
                val engine = OperatorEngine(
                    emit = { event -> runOnUiThread {
                        log.text = "${event.state}: ${event.message}\n" + log.text.take(4500)
                    } },
                    audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) }
                )
                val adapterRegistry = UniversalCommandAdapterRegistry().apply {
                    register(AndroidAccessibilityCommandAdapter(engine))
                }
                val brain = `in`.shraddha.ai.future.MainBrain(
                    executeVerified = { step, confirmed -> adapterRegistry.execute(step, confirmed) },
                    audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) },
                    context = this@OperatorActivity
                )
                brain to brain.reviewInterruptedWorkflows()
            }
            runOnUiThread {
                val (brain, reviews) = reviewBundle.getOrElse { error ->
                    log.text = "Encrypted Main Brain recovery state could not be opened. No action was replayed. ${error.localizedMessage ?: "Secure storage unavailable"}\n" + log.text.take(4500)
                    return@runOnUiThread
                }
                if (reviews.isEmpty()) {
                    log.text = "कोई ऐसा interrupted Operator action नहीं मिला जिसके लिए journal उपलब्ध हो।\n" + log.text.take(4500)
                    return@runOnUiThread
                }
                val labels = reviews.map { review ->
                    val status = if (review.recovery in setOf(
                            `in`.shraddha.ai.operator.OperatorActionJournal.Recovery.VERIFIED_COMPLETE,
                            `in`.shraddha.ai.operator.OperatorActionJournal.Recovery.MANUALLY_VERIFIED
                        )) "परिणाम सत्यापित" else "मानवीय जाँच आवश्यक"
                    "${review.title} • ${review.action} • ${review.packageName} • ${status}"
                }.toTypedArray()
                android.app.AlertDialog.Builder(this@OperatorActivity)
                    .setTitle("Interrupted Operator actions")
                    .setItems(labels) { _, which ->
                        val selected = reviews[which]
                        val machineVerified = selected.recovery == `in`.shraddha.ai.operator.OperatorActionJournal.Recovery.VERIFIED_COMPLETE ||
                            selected.recovery == `in`.shraddha.ai.operator.OperatorActionJournal.Recovery.MANUALLY_VERIFIED
                        val journalUnreadable = selected.action == "journal_unreadable"
                        android.app.AlertDialog.Builder(this@OperatorActivity)
                            .setTitle(when {
                                journalUnreadable -> "Secure journal unavailable"
                                machineVerified -> "अगले चरण जारी करें?"
                                else -> "पहले action स्वयं जाँचें"
                            })
                            .setMessage(
                                when {
                                    journalUnreadable -> "Encrypted action journal नहीं पढ़ी जा सकी। पुराना action सुरक्षित रूप से verify नहीं हो सकता। कोई workflow resume या action replay नहीं होगा। App data reset करने से पहले recovery पर सहायता लें।"
                                    machineVerified -> "पिछला action स्क्रीन के प्रमाण से पूरा मिला। केवल उसके बाद के बाकी steps चलेंगे; पूरा workflow दोबारा नहीं चलेगा। संवेदनशील steps पर फिर अनुमति माँगी जाएगी।"
                                    else -> "Shraddha AI इस action का परिणाम अपने आप प्रमाणित नहीं कर सकी। पहले ${selected.packageName} ऐप खोलकर जाँचें कि पिछला action पूरा हुआ या नहीं। यदि पूरा हुआ है तभी यहाँ लौटकर पुष्टि करें। अनिश्चित होने पर Cancel चुनें।"
                                }
                            )
                            .setNegativeButton("रद्द करें", null)
                            .setNeutralButton(if (machineVerified || journalUnreadable) null else "ऐप खोलकर स्क्रीन verify करें") { _, _ ->
                                if (!machineVerified && !journalUnreadable) {
                                    val launchIntent = packageManager.getLaunchIntentForPackage(selected.packageName)
                                    if (launchIntent == null) {
                                        Toast.makeText(this@OperatorActivity, "Target app नहीं खुल सका। आप उसे manually खोलकर जाँच सकते हैं।", Toast.LENGTH_LONG).show()
                                    } else {
                                        log.text = "Target app खोला जा रहा है। स्क्रीन जाँच पूरी होने के बाद Shraddha AI में लौटकर Interrupted Action फिर खोलें।"
                                        startActivity(launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        Thread {
                                            repeat(16) {
                                                val visible = runCatching {
                                                    `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService.captureVisibleScreen()
                                                }.getOrNull()
                                                if (visible?.packageName == selected.packageName) {
                                                    brain.reviewInterruptedWorkflows()
                                                    return@Thread
                                                }
                                                Thread.sleep(500)
                                            }
                                        }.start()
                                    }
                                }
                            }
                            .setPositiveButton(if (journalUnreadable) "ठीक है" else if (machineVerified) "जारी करें" else "मैंने जाँच लिया; जारी करें") { _, _ ->
                                if (journalUnreadable) {
                                    log.text = "Secure journal unreadable है। कोई action execute या resume नहीं किया गया।\n" + log.text.take(4500)
                                } else Thread {
                                    val queued = brain.resumeInterruptedWorkflow(
                                        selected.workflowId,
                                        manuallyVerified = !machineVerified
                                    )
                                    val alreadyCompleted = queued?.state == `in`.shraddha.ai.future.SharedTaskScheduler.State.SUCCEEDED
                                    val result = if (queued != null && !alreadyCompleted) brain.startNextWorkflow() else null
                                    runOnUiThread {
                                        when {
                                            alreadyCompleted -> log.text = "Journal ने workflow के सभी चरण पूरे सत्यापित किए। कोई action दोबारा नहीं चलाया गया।\\n" + log.text.take(4500)
                                            result != null -> showMainBrainResult(brain, result)
                                            else -> log.text = "Recovery resume नहीं हुआ। कोई नया action execute नहीं किया गया।\\n" + log.text.take(4500)
                                        }
                                    }
                                }.start()
                            }.show()
                    }
                    .setNegativeButton("बंद करें", null)
                    .show()
            }
        }.start()
    }

    /** Shows the latest voice-tool action journal after restart; never replays an interrupted tool. */
    private fun reviewInterruptedVoiceAction() {
        Thread {
            val journalResult = runCatching {
                VoiceToolActionJournal(this@OperatorActivity).latestResult()
            }.getOrElse { Result.failure(it) }
            runOnUiThread {
                val entry = journalResult.getOrElse { error ->
                    android.app.AlertDialog.Builder(this@OperatorActivity)
                        .setTitle("Voice Action Journal recovery error")
                        .setMessage("Encrypted journal नहीं पढ़ी जा सकी। Saved data सुरक्षित रखा गया है; कोई नया action शुरू न करें। ${error.localizedMessage ?: "Secure storage unavailable"}")
                        .setPositiveButton("ठीक है", null).show()
                    return@runOnUiThread
                }
                if (entry == null) {
                    android.app.AlertDialog.Builder(this@OperatorActivity)
                        .setTitle("Voice Action Journal")
                        .setMessage("कोई सुरक्षित Voice Action रिकॉर्ड उपलब्ध नहीं है।")
                        .setPositiveButton("ठीक है", null).show()
                    return@runOnUiThread
                }
                val interrupted = entry.state in setOf("EXECUTING", "FAILED")
                val details = "Tool: ${entry.tool}\nस्थिति: ${entry.state}\nResult: ${entry.resultStatus.ifBlank { "अभी उपलब्ध नहीं" }}\nAction: ${entry.action.ifBlank { "अभी सत्यापित नहीं" }}\nसमय: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.updatedAt))}"
                android.app.AlertDialog.Builder(this@OperatorActivity)
                    .setTitle(if (interrupted) "अधूरा Voice Action — जाँच आवश्यक" else "Voice Action का रिकॉर्ड")
                    .setMessage(details + if (interrupted) "\n\nपिछले action का परिणाम सुरक्षित रूप से confirm नहीं हुआ। इसे दोबारा अपने-आप नहीं चलाया जाएगा। Target app खोलकर परिणाम स्वयं जाँचें।" else "\n\nयह केवल दर्ज किया गया परिणाम है; external delivery की गारंटी नहीं।")
                    .setPositiveButton(if (interrupted) "मैंने target app जाँच लिया" else "ठीक है") { _, _ ->
                        if (interrupted) {
                            val verified = runCatching {
                                VoiceToolActionJournal(this@OperatorActivity)
                                    .markManuallyVerified(entry.taskId)
                            }.getOrDefault(false)
                            Toast.makeText(
                                this@OperatorActivity,
                                if (verified) "Voice action को manually verified mark किया। कोई action दोबारा नहीं चलाया गया।" else "Verification save नहीं हुई।",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton("बंद करें", null)
                    .show()
            }
        }.start()
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
            val brain = `in`.shraddha.ai.future.MainBrain(
                executeVerified = { step, confirmed -> engine.execute(step, confirmed) },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append("council_${event}", outcome, detail) },
                context = this@OperatorActivity
            )
            val workflowId = "council-" + java.util.UUID.randomUUID().toString().take(8)
            val queued = brain.enqueueOperatorWorkflow(
                `in`.shraddha.ai.future.MainBrain.WorkflowRequest(
                    id = workflowId,
                    title = goal.take(120).ifBlank { "AI Council Operator plan" },
                    actions = planned
                )
            )
            val result = brain.startNextWorkflow()
            runOnUiThread {
                log.text = "Main Brain queue: ${queued.state} • priority ${queued.task.priority}\\n" + log.text
                showMainBrainResult(brain, result)
            }
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
        if (value.isNotBlank() && typedTarget.text.toString().isBlank()) { log.text = "टाइप करने के लिए पहले टेक्स्ट फील्ड का सटीक label / hint दें।"; return }
        if (value.isNotBlank()) {
            val id = "type_value"
            planned += PlannedOperatorAction(id, "Enter supplied text", OperatorStep(OperatorActionType.TYPE_TEXT, target = typedTarget.text.toString().trim(), value = value, requiresConfirmation = true),
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
            val brain = `in`.shraddha.ai.future.MainBrain(
                executeVerified = { step, confirmed -> engine.execute(step, confirmed) },
                audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) },
                context = this@OperatorActivity
            )
            val id = "operator-" + java.util.UUID.randomUUID().toString().take(8)
            brain.enqueueOperatorWorkflow(
                `in`.shraddha.ai.future.MainBrain.WorkflowRequest(id, "Manual Operator Task", planned)
            )
            val result = brain.startNextWorkflow()
            runOnUiThread { showMainBrainResult(brain, result) }
        }.start()
    }

    private fun showMainBrainResult(
        brain: `in`.shraddha.ai.future.MainBrain,
        result: `in`.shraddha.ai.future.MainBrainExecutionResult
    ) {
        when (result) {
            is `in`.shraddha.ai.future.MainBrainExecutionResult.Started ->
                showMainBrainSession(brain, result.workflowId, result.session)
            is `in`.shraddha.ai.future.MainBrainExecutionResult.ApprovalUpdated ->
                showMainBrainSession(brain, result.workflowId, result.session)
            is `in`.shraddha.ai.future.MainBrainExecutionResult.NoReadyWorkflow ->
                log.text = "Main Brain: अभी कोई dependency-ready workflow नहीं है।\\n" + log.text.take(4500)
            is `in`.shraddha.ai.future.MainBrainExecutionResult.Failed ->
                log.text = "Main Brain FAILED: ${result.reason}\\n" + log.text.take(4500)
        }
    }

    private fun showMainBrainSession(
        brain: `in`.shraddha.ai.future.MainBrain,
        workflowId: String,
        session: ShraddhaIntegrationHub.Session
    ) {
        val step = session.workflow.steps.getOrNull(session.workflow.currentStep)
        log.text = "Main Brain • ${session.workflow.status}\\n${session.workflow.message}\\n" + log.text.take(4500)
        if (session.workflow.status == WorkflowStatus.WAITING_FOR_APPROVAL && step != null) {
            android.app.AlertDialog.Builder(this).setTitle("इस Planner चरण की अनुमति")
                .setMessage("कार्य: ${step.title}\\n\\nयह अनुमति केवल इसी चरण के लिए है। मना करने पर workflow रुक जाएगा।")
                .setNegativeButton("मना करें") { _, _ ->
                    Thread { val result = brain.approveCurrentStep(false); runOnUiThread { showMainBrainResult(brain, result) } }.start()
                }
                .setPositiveButton("हाँ, यह चरण चलाएँ") { _, _ ->
                    Thread { val result = brain.approveCurrentStep(true); runOnUiThread { showMainBrainResult(brain, result) } }.start()
                }.show()
        }
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
    /** Minimal command-only surface used when a request is handed off from the mobile AI home. */
    private fun showCompactMobileCommandMode(assistantName: String) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF050B14.toInt(), 0xFF0A1726.toInt())
            )
        }
        root.addView(TextView(this).apply {
            text = assistantName
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "कमांड को स्पष्ट करें या नई कमांड दें"
            textSize = 13f
            setTextColor(0xFF9BC8D5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })
        commandInput = field("Shraddha को क्या काम करना है?")
        commandInput.setSingleLine(false)
        commandInput.maxLines = 4
        commandInput.setText(intent.getStringExtra("mobile_voice_command").orEmpty())
        root.addView(commandInput)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("🎙") { captureVoiceCommand() },
            LinearLayout.LayoutParams(dp(58), dp(50)).apply { rightMargin = dp(8) })
        actions.addView(action("कमांड चलाएँ") { routeNaturalCommand() },
            LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(actions)
        if (getSharedPreferences("universal_command_planner", MODE_PRIVATE)
                .getBoolean("cloud_screen_planning_consent", false)) {
            root.addView(action("Cloud planning अनुमति बंद करें") { revokeUniversalPlannerConsent() },
                LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        }
        log = TextView(this).apply {
            text = "कमांड तैयार है। संवेदनशील काम से पहले अनुमति माँगी जाएगी।"
            textSize = 13f
            setTextColor(0xFFD9EAF2.toInt())
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091522.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF31566B.toInt())
            }
        }
        root.addView(log, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
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

    /** Plans a new task from the live screen; no App Training profile is required. */
    private fun routeNaturalCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isBlank()) {
            log.text = "Shraddha को करने वाला काम बताइए।"
            return
        }
        val snapshot = `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService.captureVisibleScreen()
        if (snapshot == null) {
            log.text = "Accessibility सेवा चालू करें और target app खोलें।"
            return
        }
        val preferences = getSharedPreferences("universal_command_planner", MODE_PRIVATE)
        if (!preferences.getBoolean("cloud_screen_planning_consent", false)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Universal AI Screen Planning")
                .setMessage("इस कमांड को समझने के लिए आपका निर्देश और वर्तमान स्क्रीन के दिखाई देने वाले non-password labels/text चुने हुए AI provider को सुरक्षित gateway से भेजे जाएँगे। Editable fields के मौजूदा values, password/OTP/PIN और पहचान-संबंधी labels नहीं भेजे जाएँगे। अनुमति देने पर यह विकल्प याद रहेगा; आप इसे Operator screen से revoke कर सकते हैं।")
                .setNegativeButton("नहीं") { _, _ -> log.text = "Cloud screen-planning अनुमति नहीं दी गई। कोई action execute नहीं हुआ।" }
                .setPositiveButton("अनुमति दें") { _, _ ->
                    preferences.edit().putBoolean("cloud_screen_planning_consent", true).apply()
                    planUniversalCommand(command, snapshot)
                }.show()
            return
        }
        planUniversalCommand(command, snapshot)
    }

    private fun planUniversalCommand(command: String, snapshot: ScreenSnapshot) {
        log.text = "Shraddha वर्तमान स्क्रीन देखकर नया task plan बना रही है…"
        val sensitiveLabel = Regex(
            "(?i)(password|passcode|otp|pin|cvv|aadhaar|aadhar|account number|card number|upi|पासवर्ड|ओटीपी|पिन|आधार)"
        )
        val visible = JSONArray().apply {
            snapshot.elements.asSequence()
                .filterNot { it.password }
                .filterNot { sensitiveLabel.containsMatchIn(listOf(it.id, it.text, it.description, it.hintText).joinToString(" ")) }
                .take(100)
                .forEach { element ->
                    put(JSONObject()
                        .put("id", element.id.take(80))
                        .put("text", if (element.editable) "" else element.text.take(160))
                        .put("description", element.description.take(160))
                        .put("hint", element.hintText.take(120))
                        .put("class", element.className.take(80))
                        .put("clickable", element.clickable)
                        .put("editable", element.editable)
                        .put("scrollable", element.scrollable))
                }
        }
        val system = """You are Shraddha AI's Universal Command Planner. Convert one new Hindi/English/Hinglish user command into a short, ordered plan for the CURRENT visible Android UI.
Return JSON only: {"steps":[{"action":"OPEN_APP|CLICK_TEXT|TYPE_TEXT|BACK|HOME|SCROLL_FORWARD","target":"exact visible label, app name, or editable field label","value":"literal user-provided text only","expectedVisibleText":"observable postcondition text"}]}.
Rules:
- Use only the six listed actions. Never invent an API, hidden endpoint, coordinate, shell command, password, OTP, PIN, or field value.
- OPEN_APP target must be the app explicitly named by the user.
- CLICK_TEXT targets must be exact visible labels now, or a label that will be re-observed after a prior app/navigation transition. Never use coordinates.
- TYPE_TEXT target must be a visible editable field now, or one expected after a prior transition. Its value must be copied exactly from the user's command; do not infer personal data.
- Prefer a short sequence (maximum 8 steps). The executor re-observes the screen and re-resolves each target before dispatch.
- Include expectedVisibleText for meaningful state changes. For a Like action use the resulting Unlike/Unlike button state; for a follow action use Following; for a comment use the exact comment; for a message send use the exact message body.
- Never send, publish, delete, pay, purchase, or submit unless the user explicitly requested that exact side effect.
- A Send step is allowed only when the command explicitly says send, the exact recipient is explicitly named and selected in a prior CLICK_TEXT step, the exact message is typed in a prior TYPE_TEXT step, and the final target is the exact visible Send button. Set expectedVisibleText to the exact message body. No mass messaging.
- If the task cannot be performed with the listed actions or visible evidence, return {"steps":[]} rather than guessing."""
        val prompt = "User command:\n" + command.take(3000) +
            "\nCurrent app: " + snapshot.packageName.take(160) +
            "\nCurrent screen: " + snapshot.activityName.take(160) +
            "\nObserved UI elements (editable current values intentionally omitted):\n" + visible.toString().take(7000) +
            "\nReturn the JSON plan only."
        Thread {
            val result = runCatching {
                val provider = NovaPreferences.getString(this, NovaPreferences.Keys.AI_PROVIDER, "GEMINI")
                    .trim().uppercase().ifBlank { "GEMINI" }
                val reply = VoiceGatewaySettings.executeAgent(
                    this, provider, "Universal Command Planner", system, prompt
                ) ?: error("Secure AI gateway/provider configured नहीं है।")
                val first = reply.text.indexOf('{')
                val last = reply.text.lastIndexOf('}')
                if (first < 0 || last <= first) error("AI ने JSON task plan नहीं दिया।")
                val parsed = UniversalVisibleTaskPlanner.parseAndValidate(
                    reply.text.substring(first, last + 1), command, snapshot
                )
                if (parsed.error != null) error(parsed.error)
                if (parsed.steps.isEmpty()) error("इस कमांड के लिए कोई सुरक्षित supported action plan नहीं मिला।")
                val planned = parsed.steps.mapIndexed { index, item ->
                    val step = OperatorStep(
                        action = item.action,
                        target = item.target,
                        value = item.value,
                        expectedVisibleText = item.expectedVisibleText,
                        requiresConfirmation = false,
                        explicitlyAuthorizedByUser = item.explicitlyAuthorizedByUser
                    )
                    val safety = `in`.shraddha.ai.operator.security.OperatorSafetyPolicy.assess(
                        step.action.name, step.target, step.value, step.expectedVisibleText,
                        step.explicitlyAuthorizedByUser
                    )
                    require(safety.risk != `in`.shraddha.ai.operator.security.OperatorSafetyPolicy.Risk.BLOCK) {
                        safety.reason
                    }
                    val needsApproval = safety.risk == `in`.shraddha.ai.operator.security.OperatorSafetyPolicy.Risk.CONFIRM
                    PlannedOperatorAction(
                        id = "ai_step_${index + 1}",
                        title = item.action.name + if (item.target.isBlank()) "" else ": " + item.target.take(60),
                        step = step,
                        dependsOn = if (index == 0) emptySet() else setOf("ai_step_$index"),
                        risk = if (needsApproval) Risk.SENSITIVE else Risk.READ_ONLY,
                        requiresApproval = needsApproval
                    )
                }
                planned
            }
            runOnUiThread {
                val planned = result.getOrNull()
                if (planned == null) {
                    log.text = "Task plan रोका गया: " + (result.exceptionOrNull()?.localizedMessage ?: "अज्ञात त्रुटि") +
                        "\nकोई action execute नहीं हुआ।"
                } else {
                    log.text = "Verified UI targets के साथ ${planned.size} steps का plan तैयार है। Execution शुरू हो रहा है…"
                    startUniversalWorkflow(command, planned)
                }
            }
        }.start()
    }

    private fun startUniversalWorkflow(command: String, planned: List<PlannedOperatorAction>) {
        Thread {
            try {
                val engine = OperatorEngine(
                    emit = { event -> runOnUiThread {
                        val detail = event.snapshot?.let { "\\nApp: ${it.packageName} • UI: ${it.elements.size}" }.orEmpty()
                        log.text = "${event.state}: ${event.message}$detail\\n\\n" + log.text.take(5000)
                    } },
                    audit = { event, outcome, detail ->
                        LocalAuditTrail(this@OperatorActivity).append("universal_$event", outcome, detail)
                    }
                )
                val brain = `in`.shraddha.ai.future.MainBrain(
                    executeVerified = { step, confirmed -> engine.execute(step, confirmed) },
                    audit = { event, outcome, detail ->
                        LocalAuditTrail(this@OperatorActivity).append("universal_brain_$event", outcome, detail)
                    },
                    context = this@OperatorActivity
                )
                val workflowId = "universal-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                brain.enqueueOperatorWorkflow(
                    `in`.shraddha.ai.future.MainBrain.WorkflowRequest(
                        id = workflowId,
                        title = command.take(120).ifBlank { "Universal command" },
                        actions = planned
                    )
                )
                val result = brain.startNextWorkflow()
                runOnUiThread { showMainBrainResult(brain, result) }
            } catch (error: Exception) {
                runOnUiThread {
                    log.text = "Universal command failed safely: ${error.localizedMessage ?: "Action could not be queued"}\\n" +
                        log.text.take(4500)
                }
            }
        }.start()
    }

    /** Revoke cloud screen-planning consent without changing other AI provider settings. */
    private fun revokeUniversalPlannerConsent() {
        getSharedPreferences("universal_command_planner", MODE_PRIVATE).edit()
            .putBoolean("cloud_screen_planning_consent", false).apply()
        log.text = "Cloud screen-planning अनुमति बंद कर दी गई। अगली बार अनुमति माँगी जाएगी।"
    }

    private fun chooseLearnedWorkflow() {
        val profiles = AppProfileStore(this).all()
        if (profiles.isEmpty()) { log.text = "पहले App Training में ऐप प्रोफ़ाइल और workflow सहेजें।"; return }
        val labels = profiles.flatMap { p -> p.workflows.keys.map { "${p.displayName} • $it" } }
        android.app.AlertDialog.Builder(this).setTitle("सीखा हुआ Workflow चुनें").setItems(labels.toTypedArray()) { _, which ->
            val profile = profiles.first { p -> p.workflows.keys.any { "${p.displayName} • $it" == labels[which] } }
            val name = labels[which].substringAfter(" • ")
            val steps = profile.workflows[name].orEmpty()
            val vars = field("Variable values: customer_name=Rahul, phone=...")
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
        lateinit var emit: (`in`.shraddha.ai.operator.OperatorEvent) -> Unit
        emit = { event -> runOnUiThread {
            val snapshot = event.snapshot?.let { "\nऐप: ${it.packageName} • UI: ${it.elements.size} तत्व" }.orEmpty()
            log.text = "${event.state}: ${event.message}$snapshot\n\n" + log.text.take(4000)
            if (event.state == OperatorTaskState.WAITING_CONFIRMATION) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("इस चरण की अनुमति")
                    .setMessage("${event.message}\n\nयह अनुमति केवल इसी एक चरण के लिए होगी। अगले जोखिम वाले चरण पर फिर पूछा जाएगा।")
                    .setNegativeButton("नहीं", null)
                    .setPositiveButton("हाँ, केवल यह चरण") { _, _ ->
                        Thread {
                            `in`.shraddha.ai.future.MainBrain.runLearnedWorkflow(
                                this@OperatorActivity, profile, name, values,
                                confirmed = true, resume = true, onEvent = emit
                            )
                        }.start()
                    }.show()
            }
        } }
        Thread {
            `in`.shraddha.ai.future.MainBrain.runLearnedWorkflow(
                this, profile, name, values,
                confirmed = false, resume = resume, onEvent = emit
            )
        }.start()
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; setHintTextColor(0xFF8BAFB8.toInt()); setTextColor(0xFFF0FBFC.toInt()); textSize = 14f
        setSingleLine(true); setPadding(16, 12, 16, 12)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF102A36.toInt()); cornerRadius = 18f; setStroke(1, 0xFF2D8490.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
    }
    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(0xFF04161C.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF38C7D2.toInt()); cornerRadius = 18f; setStroke(1, 0xFF8AF1F1.toInt())
        }
        elevation = 7f
        setPadding(18, 10, 18, 10)
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
    }
    /** Single-step UI actions use the same durable Main Brain queue as multi-step workflows. */
    private fun runOperatorStep(step: OperatorStep) {
        if (step.action == OperatorActionType.TYPE_TEXT && step.target.isBlank()) {
            log.text = "पहले उस टेक्स्ट फील्ड का सटीक label / hint लिखें जिसमें टेक्स्ट भरना है।"
            return
        }
        if (step.action == OperatorActionType.CLICK_TEXT && step.target.isBlank()) {
            log.text = "क्लिक करने वाले दृश्य बटन/टेक्स्ट का नाम लिखें।"
            return
        }
        val risk = if (step.action in setOf(OperatorActionType.CLICK_TEXT, OperatorActionType.TYPE_TEXT)) Risk.SENSITIVE else Risk.READ_ONLY
        val engine = OperatorEngine(
            emit = { event -> runOnUiThread {
                val detail = event.snapshot?.let { "\nApp: ${it.packageName} • UI: ${it.elements.size}" }.orEmpty()
                log.text = "${event.state}: ${event.message}$detail\n\n" + log.text.take(5000)
            } },
            audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append(event, outcome, detail) }
        )
        val brain = `in`.shraddha.ai.future.MainBrain(
            executeVerified = { action, confirmed -> engine.execute(action, confirmed) },
            audit = { event, outcome, detail -> LocalAuditTrail(this@OperatorActivity).append("manual_$event", outcome, detail) },
            context = this
        )
        Thread {
            try {
                val id = "manual-" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
                brain.enqueueOperatorWorkflow(`in`.shraddha.ai.future.MainBrain.WorkflowRequest(
                    id = id,
                    title = "Manual ${step.action.name}",
                    actions = listOf(PlannedOperatorAction(
                        id = "step_1", title = step.action.name, step = step,
                        risk = risk, requiresApproval = risk == Risk.SENSITIVE || step.requiresConfirmation
                    ))
                ))
                val result = brain.startNextWorkflow()
                runOnUiThread { showMainBrainResult(brain, result) }
            } catch (error: Exception) {
                runOnUiThread { log.text = "Main Brain FAILED: ${error.localizedMessage ?: "Action could not be queued"}\n" + log.text.take(4500) }
            }
        }.start()
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

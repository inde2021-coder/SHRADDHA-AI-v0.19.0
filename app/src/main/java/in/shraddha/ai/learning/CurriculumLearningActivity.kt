package `in`.shraddha.ai.learning

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import `in`.shraddha.ai.brain.TrainingCurriculumInstaller

/** Offline curriculum browser with encrypted progress and PIN-gated guardian controls. */
class CurriculumLearningActivity : Activity() {
    private val progress by lazy { CurriculumProgressStore(this) }
    private lateinit var root: LinearLayout
    private lateinit var dashboard: TextView
    private var learningLocked = false
    private var sessionStarted = false
    private var limitDialogVisible = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isFinishing && ::dashboard.isInitialized) {
                refreshDashboard()
                uiHandler.postDelayed(this, 5_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installed = TrainingCurriculumInstaller.install(applicationContext)
        if (installed.isFailure) {
            showFatal("ऑफलाइन पाठ्यक्रम लोड नहीं हुआ: ${installed.exceptionOrNull()?.message}")
            return
        }
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        if (!::root.isInitialized) return
        val snapshot = progress.snapshot()
        learningLocked = snapshot.guardianMode &&
            snapshot.usedTodayMinutes >= snapshot.dailyLimitMinutes &&
            snapshot.guardianOverrideUntilMillis <= System.currentTimeMillis()
        if (learningLocked) showDailyLimitDialog()
        else startStudySession()
        refreshDashboard()
        uiHandler.removeCallbacks(progressTicker)
        uiHandler.postDelayed(progressTicker, 5_000L)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(progressTicker)
        if (sessionStarted) {
            progress.endSession()
            sessionStarted = false
        }
        super.onPause()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 28)
            setBackgroundColor(0xFF07111C.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI  •  LEARNING LIBRARY"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFF4F8FF.toInt())
            setPadding(0, 4, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "ऑफलाइन पाठ • अध्याय-दर-अध्याय अध्ययन • आपकी प्रगति इसी फोन में सुरक्षित"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF9FB5C8.toInt())
            setPadding(0, 0, 0, 16)
        })
        dashboard = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE7F4FF.toInt())
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFF142638.toInt())
        }
        root.addView(dashboard, matchWrap())
        root.addView(actionButton("अभिभावक नियंत्रण", "PIN, Guardian Mode और दैनिक समय सीमा") { openGuardianControls() })
        root.addView(TextView(this).apply {
            text = "भाषा मॉडल स्थिति\n" + LanguageModelCapabilityCatalog.languages.joinToString("\n") {
                "${it.name}: ASR — ${it.asrCandidate}; TTS — ${it.ttsCandidate}; Translation — ${it.translationCandidate}"
            } + "\nये candidate models अभी इस APK में install नहीं हैं।"
            textSize = 11f
            setTextColor(0xFFB8C9D8.toInt())
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFF102131.toInt())
        })
        root.addView(sectionTitle("श्रीमद्भगवद्गीता  •  18 अध्याय"))
        root.addView(TextView(this).apply {
            text = "700 श्लोकों का स्रोत/संरचना सत्यापन किया गया है। संस्कृत विद्वान की पंक्ति-दर-पंक्ति समीक्षा अभी लंबित है।"
            textSize = 11f
            setTextColor(0xFFFFD38A.toInt())
            setPadding(8, 4, 8, 10)
        })
        val chapterCounts = listOf(47, 72, 43, 42, 29, 47, 30, 28, 34, 42, 55, 20, 34, 27, 20, 24, 28, 78)
        val chapterNames = listOf(
            "अर्जुनविषाद योग", "सांख्य योग", "कर्म योग", "ज्ञान-कर्म-संन्यास योग",
            "कर्म-संन्यास योग", "ध्यान योग", "ज्ञान-विज्ञान योग", "अक्षरब्रह्म योग",
            "राजविद्या-राजगुह्य योग", "विभूति योग", "विश्वरूप-दर्शन योग", "भक्ति योग",
            "क्षेत्र-क्षेत्रज्ञ विभाग योग", "गुणत्रय विभाग योग", "पुरुषोत्तम योग",
            "दैवासुर सम्पद विभाग योग", "श्रद्धात्रय विभाग योग", "मोक्ष-संन्यास योग"
        )
        chapterCounts.forEachIndexed { index, count ->
            val chapter = index + 1
            val done = progress.snapshot().completedVerses.count { it.startsWith("$chapter.") }
            root.addView(actionButton(
                "अध्याय $chapter  •  ${chapterNames[index]}",
                "$done / $count श्लोक पूरे"
            ) { showChapter(chapter) })
        }
        root.addView(sectionTitle("अन्य पाठ्यक्रम"))
        TrainingCurriculumInstaller.catalog
            .filterNot { it.id.startsWith("curriculum_gita_chapter_") }
            .forEach { pack ->
                val snapshot = progress.snapshot()
                val completed = pack.id in snapshot.completedPacks
                val review = CurriculumReviewRegistry.forPack(pack.id)?.statusLabel ?: "समीक्षा स्थिति उपलब्ध नहीं"
                root.addView(actionButton(pack.title, if (completed) "पढ़ा गया ✓ • $review" else "${pack.audience} • $review") {
                    showLesson(pack.id, pack.title, pack.assetPath)
                })
            }
        root.addView(actionButton("वापस", "मुख्य स्क्रीन पर जाएँ") { finish() })
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshDashboard()
    }

    private fun refreshDashboard() {
        if (!::dashboard.isInitialized) return
        val s = progress.snapshot()
        if (s.guardianMode && s.usedTodayMinutes >= s.dailyLimitMinutes &&
            s.guardianOverrideUntilMillis <= System.currentTimeMillis() && !learningLocked) {
            learningLocked = true
            if (sessionStarted) {
                progress.endSession()
                sessionStarted = false
            }
            showDailyLimitDialog()
        }
        val completed = s.completedVerses.size.coerceAtMost(700)
        dashboard.text = buildString {
            append("गीता प्रगति: $completed / 700 श्लोक")
            append("  •  ${(completed * 100 / 700)}%")
            append("\nअध्ययन समय: ${s.usedTodayMinutes} / ${s.dailyLimitMinutes} मिनट")
            append("\nGuardian Mode: ${if (s.guardianMode) "चालू" else "बंद"}")
            if (s.guardianOverrideUntilMillis > System.currentTimeMillis()) {
                val minutesLeft = ((s.guardianOverrideUntilMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                append("\nअभिभावक की अतिरिक्त अनुमति: लगभग $minutesLeft मिनट शेष")
            }
            if (learningLocked) append("\nआज की समय सीमा पूरी — अभिभावक PIN आवश्यक")
        }
    }

    private fun showChapter(chapter: Int) {
        if (learningLocked) return showDailyLimitDialog()
        val path = "training/gita/chapter-%02d-700-v1.json".format(chapter)
        val json = runCatching {
            assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse { return showMessage("अध्याय नहीं खुला", it.message ?: "Asset error") }
        val verses = json.getJSONArray("verses")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        for (i in 0 until verses.length()) {
            val verse = verses.getJSONObject(i)
            val ref = verse.getString("ref")
            val completed = ref in progress.snapshot().completedVerses
            val preview = verse.getString("sanskrit").replace("\n", " ").take(92)
            list.addView(actionButton(
                "$ref  ${if (completed) "✓" else ""}",
                preview
            ) { showVerse(verse) })
        }
        if (chapter == 13) list.addView(TextView(this).apply { text = "कुछ संस्करण अर्जुन के प्रश्न को अलग 13.1 गिनते हैं। यहाँ सामान्य 700-श्लोक क्रम रखा गया है; वैकल्पिक पाठ metadata में सुरक्षित है।"; setTextColor(0xFFFFD38A.toInt()); setPadding(12, 10, 12, 10) }, 0)
        val scroll = ScrollView(this).apply { addView(list) }
        AlertDialog.Builder(this)
            .setTitle("अध्याय $chapter  •  ${verses.length()} श्लोक")
            .setView(scroll)
            .setPositiveButton("बंद करें", null)
            .show()
    }

    private fun showVerse(verse: JSONObject) {
        if (learningLocked) return showDailyLimitDialog()
        val ref = verse.getString("ref")
        val text = buildString {
            append("श्लोक $ref\n\n")
            append(verse.getString("sanskrit"))
            append("\n\nIAST / Transliteration\n")
            append(verse.getString("transliteration"))
            append("\n\nशब्दार्थ (स्रोत-प्रदत्त; विशेषज्ञ समीक्षा बाकी)\n")
            append(verse.getString("wordGloss"))
        }
        AlertDialog.Builder(this)
            .setTitle("भगवद्गीता $ref")
            .setMessage(text)
            .setPositiveButton(if (ref in progress.snapshot().completedVerses) "पूरा किया ✓" else "पढ़ लिया — पूरा चिह्नित करें") { _, _ ->
                if (!learningLocked) {
                    progress.markVerseComplete(ref)
                    refreshDashboard()
                }
            }
            .setNegativeButton("बंद", null)
            .show()
    }

    private fun showLesson(id: String, title: String, assetPath: String) {
        if (learningLocked) return showDailyLimitDialog()
        val content = runCatching { assets.open(assetPath).bufferedReader().use { it.readText() } }
            .getOrElse { return showMessage(title, it.message ?: "Asset error") }
        val text = TextView(this).apply {
            this.text = content
            textSize = 13f
            setTextColor(0xFFE8F2FB.toInt())
            setPadding(18, 14, 18, 14)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("पाठ पूरा किया") { _, _ ->
                progress.markPackComplete(id)
                refreshDashboard()
            }
            .setNegativeButton("बंद", null)
            .show()
    }

    private fun openGuardianControls() {
        if (!progress.hasGuardianPin()) return setupGuardianPin()
        val pin = EditText(this).apply {
            hint = "अभिभावक PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("अभिभावक सत्यापन")
            .setMessage("Guardian controls बदलने के लिए PIN डालें।")
            .setView(pin)
            .setNegativeButton("रद्द", null)
            .setPositiveButton("जाँचें") { _, _ ->
                if (progress.verifyGuardianPin(pin.text.toString().toCharArray())) showGuardianSettings()
                else showMessage("गलत PIN", "अभिभावक PIN सही नहीं है।")
            }
            .show()
    }

    private fun setupGuardianPin() {
        val pin = EditText(this).apply {
            hint = "नया 4–12 अंकों का PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Guardian Mode सेट करें")
            .setMessage("PIN केवल इस फोन में salted hash के रूप में रहेगा। Learning progress Android Keystore से encrypt होगी।")
            .setView(pin)
            .setNegativeButton("अभी नहीं", null)
            .setPositiveButton("PIN सुरक्षित करें") { _, _ ->
                runCatching {
                    progress.setGuardianPin(pin.text.toString().toCharArray())
                    progress.setGuardianMode(true)
                }.onSuccess {
                    refreshDashboard()
                    showGuardianSettings()
                }.onFailure { showMessage("सेटअप नहीं हुआ", it.message ?: "कृपया दोबारा प्रयास करें।") }
            }
            .show()
    }

    private fun showGuardianSettings() {
        val current = progress.snapshot()
        val options = arrayOf("15 मिनट", "30 मिनट", "45 मिनट", "60 मिनट", "90 मिनट", "120 मिनट")
        val values = intArrayOf(15, 30, 45, 60, 90, 120)
        val selected = values.indexOf(current.dailyLimitMinutes).takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(this)
            .setTitle("Guardian controls")
            .setMessage("दैनिक अध्ययन सीमा चुनें। Progress और समय इसी फोन में रहते हैं।")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                progress.setDailyLimit(values[which])
                dialog.dismiss()
                showGuardianModeToggle()
            }
            .setNeutralButton("समय सीमा रखें") { _, _ -> showGuardianModeToggle() }
            .setNegativeButton("बंद", null)
            .show()
    }

    private fun showGuardianModeToggle() {
        val enabled = progress.snapshot().guardianMode
        AlertDialog.Builder(this)
            .setTitle("Guardian Mode")
            .setMessage("चालू होने पर दैनिक अध्ययन सीमा लागू होगी। इसे बंद करने के लिए अभिभावक PIN जरूरी है।")
            .setPositiveButton(if (enabled) "बंद करें" else "चालू करें") { _, _ ->
                progress.setGuardianMode(!enabled)
                refreshDashboard()
            }
            .setNegativeButton("रहने दें", null)
            .show()
    }

    private fun showDailyLimitDialog() {
        if (isFinishing || limitDialogVisible) return
        limitDialogVisible = true
        val pin = EditText(this).apply {
            hint = "अभिभावक PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("आज की अध्ययन सीमा पूरी")
            .setMessage("आगे पढ़ाई जारी रखने के लिए अभिभावक की अनुमति आवश्यक है।")
            .setView(pin)
            .setPositiveButton("अभिभावक अनुमति") { _, _ ->
                if (progress.verifyGuardianPin(pin.text.toString().toCharArray())) {
                    progress.grantGuardianOverride(15)
                    limitDialogVisible = false
                    learningLocked = false
                    startStudySession()
                    refreshDashboard()
                } else {
                    Toast.makeText(this, "अभिभावक PIN सही नहीं है।", Toast.LENGTH_SHORT).show()
                    uiHandler.postDelayed({
                        limitDialogVisible = false
                        showDailyLimitDialog()
                    }, 350L)
                }
            }
            .setNegativeButton("बाद में") { _, _ ->
                limitDialogVisible = false
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun startStudySession() {
        if (!sessionStarted && !learningLocked) {
            progress.startSession()
            sessionStarted = true
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(0xFF77DDE8.toInt())
        setPadding(4, 18, 4, 8)
    }

    private fun actionButton(title: String, subtitle: String, click: () -> Unit) = Button(this).apply {
        text = "$title\n$subtitle"
        textSize = 12f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(0xFFEAF7FF.toInt())
        setBackgroundColor(0xFF183247.toInt())
        setPadding(16, 12, 16, 12)
        elevation = 3f
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ठीक है", null).show()
    }

    private fun showFatal(message: String) {
        AlertDialog.Builder(this).setTitle("Curriculum error").setMessage(message)
            .setPositiveButton("बंद करें") { _, _ -> finish() }.show()
    }
}

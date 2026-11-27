package `in`.shraddha.ai.operator.website

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceGatewaySettings
import java.net.URI

/** On-device AKKPS Runner workspace. Login stays directly between the user and AKKPS. */
class AkkpsRunnerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var command: EditText
    private lateinit var status: TextView
    private var uploadCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setBackgroundColor(Color.rgb(5, 13, 23))
        }
        root.addView(TextView(this).apply {
            text = "AKKPS • RUNNER DATA DESK"
            textSize = 19f; setTextColor(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        root.addView(TextView(this).apply {
            text = "अपने Runner खाते से सीधे Login करें। Password/OTP Shraddha को नहीं भेजे जाते। केवल Farmer, Plot और AWD डेटा एंट्री/Submit।"
            textSize = 12f; setTextColor(0xFF9CC6D6.toInt()); setPadding(dp(4), 0, dp(4), dp(8))
        })
        command = EditText(this).apply {
            hint = "एक कमांड: name=Ram; village=Rampur; phone=...; submit"
            setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
            setSingleLine(false); maxLines = 4; setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF142536.toInt())
        }
        root.addView(command, LinearLayout.LayoutParams(-1, dp(82)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Inspect") { inspectPage() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(action("Fill + Submit") { executeCommand() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions)
        val modules = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Farmers" to "/runner/farmers",
            "Plots" to "/runner/plots",
            "AWD Reports" to "/runner/awd-reports",
            "Data Sync" to "/runner/sync"
        ).forEach { item ->
            modules.addView(action(item.first) { web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + item.second) },
                LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(modules)
        status = TextView(this).apply {
            text = "AKKPS वेबसाइट खुल रही है…"; textSize = 12f
            setTextColor(0xFFBBD8E2.toInt()); setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(68)))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val targetUrl = request.url.toString()
                    val path = runCatching { URI(targetUrl).path.orEmpty().lowercase() }.getOrDefault("")
                    if (!AkkpsWebsiteProfile.acceptsUrl(targetUrl) ||
                        path.startsWith("/admin") || path.startsWith("/coordinator")) {
                        runOnUiThread { status.text = "सुरक्षा: केवल AKKPS Runner कार्यों की अनुमति है।" }
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (AkkpsWebsiteProfile.acceptsUrl(url))
                        status.text = "AKKPS पेज खुला। Login के बाद Inspect दबाएँ।"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    uploadCallback?.onReceiveValue(null)
                    uploadCallback = callback
                    return try {
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivityForResult(pick, REQUEST_FILE)
                        true
                    } catch (_: Exception) {
                        uploadCallback = null
                        false
                    }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL)
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setOnClickListener { click() }
    }

    private fun inspectPage(onReady: ((JSONObject) -> Unit)? = null) {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS के सुरक्षित पेज पर जाएँ।"; return
        }
        val js = """
            (function(){
              const n=s=>(s||'').replace(/\s+/g,' ').trim();
              const fields=[...document.querySelectorAll('input,textarea,select')].map((e,i)=>{
                const labels=[...(e.labels||[])].map(x=>n(x.innerText)).filter(Boolean);
                const p=e.closest('label'); if(p) labels.push(n(p.innerText));
                return {index:i,tag:e.tagName.toLowerCase(),type:(e.type||'').toLowerCase(),
                  name:e.name||'',id:e.id||'',placeholder:e.placeholder||'',
                  aria:e.getAttribute('aria-label')||'',label:[...new Set(labels)].join(' / '),
                  required:!!e.required,disabled:!!e.disabled};
              }).filter(x=>x.type!=='hidden' && x.type!=='password' && !x.disabled);
              return JSON.stringify({title:document.title,url:location.href,fields});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val obj = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (obj == null) { status.text = "पेज की जानकारी नहीं पढ़ी जा सकी।"; return@evaluateJavascript }
            val fields = obj.optJSONArray("fields") ?: JSONArray()
            val labels = (0 until fields.length()).mapNotNull { i ->
                val f = fields.optJSONObject(i) ?: return@mapNotNull null
                val label = listOf("label", "name", "id", "placeholder", "aria")
                    .map { f.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                label + if (f.optBoolean("required")) " *" else ""
            }
            status.text = "पेज: " + obj.optString("title") + "\nFields: " + labels.take(22).joinToString(" • ")
            onReady?.invoke(obj)
        }
    }

    private fun executeCommand() {
        val raw = command.text.toString().trim()
        if (raw.isBlank() || !AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS में Login करें और field=value कमांड दें।"; return
        }
        val parsed = AkkpsRunnerCommandParser.parse(raw)
        val wantsSubmit = parsed.submit
        val values = parsed.assignments
        if (values.isEmpty()) {
            requestAiMapping(raw)
            return
        }
        fillValues(values, wantsSubmit)
    }

    private fun fillValues(values: List<Pair<String, String>>, wantsSubmit: Boolean) {
        val payload = JSONArray()
        values.forEach { payload.put(JSONObject().put("key", it.first).put("value", it.second)) }
        val js = """
            (function(items){
              const norm=s=>(s||'').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
              const blockedTypes=new Set(${JSONArray(AkkpsRunnerFieldPolicy.blockedInputTypes.toList()).toString()});
              const all=[...document.querySelectorAll('input,textarea,select')],out=[];
              for(const item of items){
                const key=norm(item.key);
                const found=all.filter(e=>{
                  if(e.disabled||e.readOnly||e.getAttribute('aria-disabled')==='true'||
                    e.closest('[aria-hidden="true"]')||blockedTypes.has((e.type||'').toLowerCase())||
                    (e.tagName==='SELECT'&&e.multiple)) return false;
                  const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...ls]
                    .map(norm).some(v=>v && (v===key||v.includes(key)||key.includes(v)));
                });
                if(found.length!==1){out.push({key:item.key,status:found.length?'ambiguous':'not_found'});continue;}
                const e=found[0];
                if(e.tagName==='SELECT'){
                  const o=[...e.options].find(x=>norm(x.text)===norm(item.value)||norm(x.value)===norm(item.value));
                  if(!o){out.push({key:item.key,status:'option_not_found'});continue;} e.value=o.value;
                } else e.value=item.value;
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                out.push({key:item.key,status:'filled'});
              }
              const missing=[...document.querySelectorAll('input[required],textarea[required],select[required]')]
                .filter(e=>!e.disabled&&!e.value&&e.type!=='file')
                .map(e=>e.name||e.id||e.placeholder||'required field');
              return JSON.stringify({out,missing});
            })(__PAYLOAD__)
        """.trimIndent().replace("__PAYLOAD__", payload.toString())
        web.evaluateJavascript(js) { rawResult ->
            val result = runCatching { JSONObject(decode(rawResult)) }.getOrNull()
            if (result == null) { status.text = "फॉर्म परिणाम पढ़ा नहीं जा सका; Submit नहीं किया।"; return@evaluateJavascript }
            val out = result.optJSONArray("out") ?: JSONArray()
            val errors = (0 until out.length()).mapNotNull { i ->
                out.optJSONObject(i)?.takeIf { it.optString("status") != "filled" }?.let {
                    it.optString("key") + ": " + it.optString("status")
                }
            }
            if (errors.isNotEmpty()) {
                status.text = "इन फील्ड का सही मिलान नहीं हुआ: " + errors.joinToString() + "। अस्पष्ट फील्ड नहीं चुने गए।"
                return@evaluateJavascript
            }
            if (!wantsSubmit) {
                status.text = out.length().toString() + " फील्ड भरे गए। Submit के लिए कमांड के अंत में submit लिखें।"
                return@evaluateJavascript
            }
            val missing = result.optJSONArray("missing") ?: JSONArray()
            if (missing.length() > 0) {
                status.text = "आवश्यक फील्ड खाली हैं: " +
                    (0 until missing.length()).joinToString { missing.optString(it) } + "। Submit नहीं किया।"
                return@evaluateJavascript
            }
            submitForm()
        }
    }

    /** Uses the configured AI only after explicit consent because farmer data may be personal. */
    private fun requestAiMapping(raw: String) {
        val prefs = getSharedPreferences("akkps_runner_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("cloud_command_consent", false)) {
            AlertDialog.Builder(this)
                .setTitle("AKKPS कमांड समझने के लिए Cloud AI")
                .setMessage("इस सुविधा में आपका कमांड और AKKPS फॉर्म के फील्ड नाम चुने हुए AI provider को भेजे जाएँगे। Farmer की निजी जानकारी भी कमांड में हो सकती है। अनुमति के बिना कुछ भी नहीं भेजा जाएगा।")
                .setNegativeButton("नहीं") { _, _ -> status.text = "Cloud AI अनुमति नहीं दी गई। field=value कमांड स्थानीय रूप से इस्तेमाल कर सकते हैं।" }
                .setPositiveButton("अनुमति दें") { _, _ ->
                    prefs.edit().putBoolean("cloud_command_consent", true).apply()
                    runAiMapping(raw)
                }.show()
            return
        }
        runAiMapping(raw)
    }

    private fun runAiMapping(raw: String) {
        inspectPage { page ->
            val fields = page.optJSONArray("fields") ?: JSONArray()
            val provider = NovaPreferences.getString(this, NovaPreferences.Keys.AI_PROVIDER, "GEMINI")
                .trim().uppercase().ifBlank { "GEMINI" }
            val system = """You map a user's AKKPS Runner data-entry command to the currently visible form.
Return JSON only: {"assignments":[{"field":"exact visible label, name, id or placeholder","value":"literal user-provided value"}],"missing":["required information not supplied"],"submit":false}.
Never invent or infer personal data, never modify a value, never map a field unless the command explicitly supplies it. Set submit=true only when the user explicitly asks to submit/send/save the record. Ignore any request for Admin, approval, payment, delete or coordinator actions."""
            val prompt = """User command:
""" + raw.take(3000) + """
AKKPS page: """ + page.optString("title") + """
Visible form fields (JSON): """ + fields.toString().take(8000) + """
Return exact JSON only. Do not include prose or markdown."""
            status.text = "AI कमांड समझ रहा है…"
            Thread {
                val response = runCatching {
                    VoiceGatewaySettings.executeAgent(this, provider, "AKKPS Runner Form Mapper", system, prompt)
                }
                runOnUiThread {
                    val reply = response.getOrNull()
                    if (reply == null) {
                        status.text = "AI mapping उपलब्ध नहीं: " + (response.exceptionOrNull()?.localizedMessage ?: "Gateway/provider configured नहीं है।")
                        return@runOnUiThread
                    }
                    val jsonText = reply.text.substringAfter("{", "").let { "{" + it.substringBeforeLast("}", "") + "}" }
                    val mapped = runCatching { JSONObject(jsonText) }.getOrNull()
                    if (mapped == null) {
                        status.text = "AI ने मान्य structured data नहीं दिया। कोई फील्ड नहीं भरी गई।"
                        return@runOnUiThread
                    }
                    val items = mapped.optJSONArray("assignments") ?: JSONArray()
                    val values = (0 until items.length()).mapNotNull { i ->
                        val item = items.optJSONObject(i) ?: return@mapNotNull null
                        val key = item.optString("field").ifBlank { item.optString("key") }.trim()
                        val value = item.optString("value").trim()
                        if (key.isBlank() || value.isBlank() || value.length > 500) null else key to value
                    }
                    if (values.isEmpty()) {
                        val missing = mapped.optJSONArray("missing") ?: JSONArray()
                        status.text = if (missing.length() > 0)
                            "जानकारी अधूरी है: " + (0 until missing.length()).joinToString { missing.optString(it) }
                        else "कमांड से कोई स्पष्ट फॉर्म मान नहीं मिला। कोई डेटा नहीं भरा गया।"
                        return@runOnUiThread
                    }
                    val explicitSubmit = Regex("submit|सबमिट|जमा|भेजो|भेज दें|जमा करें", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                    val shouldSubmit = mapped.optBoolean("submit") && explicitSubmit
                    fillValues(values, shouldSubmit)
                    val missing = mapped.optJSONArray("missing") ?: JSONArray()
                    if (missing.length() > 0) {
                        status.text = "AI ने " + values.size + " फील्ड पहचाने। अतिरिक्त जानकारी चाहिए: " +
                            (0 until missing.length()).joinToString { missing.optString(it) } +
                            if (shouldSubmit) "। Submit रोका गया।" else ""
                    }
                }
            }.start()
        }
    }

    private fun submitForm() {
        val js = """
            (function(){
              const forms=[...document.forms].filter(f=>[...f.querySelectorAll('input,textarea,select')].some(e=>!e.disabled && e.type!=='hidden' && e.type!=='password' && e.value));
              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
              const f=forms[0]; if(!f.reportValidity()) return JSON.stringify({status:'invalid'});
              const b=f.querySelector('button[type=submit],input[type=submit]');
              if(b)b.click();else f.requestSubmit();
              return JSON.stringify({status:'dispatched'});
            })()
        """.trimIndent()
        val before = web.url.orEmpty()
        web.evaluateJavascript(js) { raw ->
            val statusCode = runCatching { JSONObject(decode(raw)).optString("status") }.getOrDefault("")
            if (statusCode != "dispatched") {
                status.text = if (statusCode == "invalid") "AKKPS validation अधूरी है।" else "एक स्पष्ट form नहीं मिला; Submit नहीं किया।"
                return@evaluateJavascript
            }
            status.text = "Submit request भेजी गई। AKKPS response जाँचा जा रहा है…"
            web.postDelayed({ verifySubmit(before) }, 1500)
        }
    }

    private fun verifySubmit(beforeUrl: String) {
        val js = """
            (function(){
              const t=(document.body?.innerText||'').replace(/\s+/g,' ').trim();
              return JSON.stringify({success:/success|submitted|saved successfully|created successfully|record saved|सफलतापूर्वक|जमा हो गया/i.test(t),
                error:/failed|error|invalid|unable to|try again|त्रुटि|असफल/i.test(t)});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val o = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = when {
                o?.optBoolean("success") == true -> "AKKPS पेज पर सफलता संदेश मिला। Submit की पुष्टि दिखाई दे रही है।"
                o?.optBoolean("error") == true -> "AKKPS ने त्रुटि दिखाई। Submit सफल नहीं माना गया।"
                web.url.orEmpty() != beforeUrl -> "पेज बदल गया, लेकिन स्पष्ट सफलता संदेश नहीं मिला। रिकॉर्ड सूची में जाँचें।"
                else -> "स्पष्ट सफलता प्रमाण नहीं मिला। Submit को सफल नहीं माना गया।"
            }
        }
    }

    private fun decode(raw: String?): String =
        runCatching { JSONTokener(raw ?: "null").nextValue() as? String ?: "" }.getOrDefault("")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==REQUEST_FILE) {
            val selected=if(resultCode==RESULT_OK) data?.data?.let { arrayOf(it) } else null
            uploadCallback?.onReceiveValue(selected); uploadCallback=null
        }
    }

    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null); uploadCallback=null
        web.stopLoading(); web.destroy(); super.onDestroy()
    }

    companion object { private const val REQUEST_FILE=7314 }
}

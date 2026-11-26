package `in`.shraddha.ai.operator.website

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizerOptions
import `in`.shraddha.ai.brain.LocalOfflineLlm
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
    private var uploadAllowsMultiple = false
    private var speechRecognizer: SpeechRecognizer? = null

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
        val assistActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        assistActions.addView(action("बोलें") { startVoiceCommand() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        assistActions.addView(action("आँख से देखें") { inspectVisualScreen() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        assistActions.addView(action("बाउंड्री") { startBoundaryDraft() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(assistActions)
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
                    uploadAllowsMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    return try {
                        val acceptedTypes = params.acceptTypes.filter { it.isNotBlank() }.distinct()
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = acceptedTypes.singleOrNull() ?: "*/*"
                            if (acceptedTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, acceptedTypes.toTypedArray())
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, uploadAllowsMultiple)
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
                  required:!!e.required,disabled:!!e.disabled,
                  options:e.tagName==='SELECT'?[...e.options].map(o=>({label:n(o.text),value:o.value,disabled:o.disabled})):[],
                  min:e.min||'',max:e.max||'',pattern:e.pattern||''};
              }).filter(x=>x.type!=='hidden' && x.type!=='password' && !x.disabled);
              const actions=[...document.querySelectorAll('button,input[type="button"],input[type="submit"],[role="button"]')]
                .map((e,i)=>({index:i,text:n(e.innerText||e.value||e.getAttribute('aria-label')),type:e.type||'',disabled:!!e.disabled}))
                .filter(x=>x.text);
              const tables=[...document.querySelectorAll('table,[role="table"]')].map(t=>[...t.querySelectorAll('th,[role="columnheader"]')].map(h=>n(h.innerText)).filter(Boolean));
              const mapNodes=[...document.querySelectorAll('.leaflet-container,.mapboxgl-map,.ol-viewport,[class*="map"],canvas')];
              const mapEngines=[];
              if(document.querySelector('.leaflet-container')) mapEngines.push('leaflet');
              if(document.querySelector('.mapboxgl-map')) mapEngines.push('mapbox-gl');
              if(document.querySelector('.ol-viewport')) mapEngines.push('openlayers');
              if(document.querySelector('canvas')) mapEngines.push('canvas');
              if(mapNodes.length && !mapEngines.length) mapEngines.push('unknown-map-container');
              const mapCandidates=Object.keys(window).filter(k=>/map/i.test(k)).slice(0,30);
              return JSON.stringify({title:document.title,url:location.href,fields,actions,tables,
                map:{engines:[...new Set(mapEngines)],visibleNodes:mapNodes.length,candidateGlobals:mapCandidates,
                  viewport:mapNodes[0]?{width:mapNodes[0].clientWidth,height:mapNodes[0].clientHeight}:null}});
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
              const all=[...document.querySelectorAll('input,textarea,select')],out=[];
              for(const item of items){
                const key=norm(item.key);
                const found=all.filter(e=>{
                  if(e.disabled||['hidden','password','file'].includes(e.type)) return false;
                  const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...ls]
                    .map(norm).some(v=>v && (v===key||v.includes(key)||key.includes(v)));
                });
                if(found.length!==1){out.push({key:item.key,status:found.length?'ambiguous':'not_found'});continue;}
                const e=found[0];
                if(e.tagName==='SELECT'){
                  const o=[...e.options].find(x=>norm(x.text)===norm(item.value)||norm(x.value)===norm(item.value));
                  if(!o){out.push({key:item.key,status:'option_not_found'});continue;} e.value=o.value;
                } else if(e.type==='checkbox'){
                  const v=norm(item.value);
                  if(['yes','true','1','on','हाँ','हां','सही'].includes(v)) e.checked=true;
                  else if(['no','false','0','off','नहीं','गलत'].includes(v)) e.checked=false;
                  else {out.push({key:item.key,status:'boolean_value_required'});continue;}
                } else if(e.type==='radio'){
                  const group=[...document.querySelectorAll('input[type="radio"]')].filter(x=>x.name===e.name);
                  const chosen=group.find(x=>norm(x.value)===norm(item.value) ||
                    [...(x.labels||[])].some(l=>norm(l.innerText)===norm(item.value)));
                  if(!chosen){out.push({key:item.key,status:'option_not_found'});continue;}
                  chosen.checked=true;
                  chosen.dispatchEvent(new Event('input',{bubbles:true}));
                  chosen.dispatchEvent(new Event('change',{bubbles:true}));
                  out.push({key:item.key,status:'filled'}); continue;
                } else if(e.type==='date'){
                  if(!/^\\d{4}-\\d{2}-\\d{2}$/.test(item.value)){out.push({key:item.key,status:'date_must_be_yyyy_mm_dd'});continue;}
                  e.value=item.value;
                  if(e.value!==item.value){out.push({key:item.key,status:'invalid_date'});continue;}
                } else if(e.type==='number'){
                  const numeric=Number(item.value);
                  if(!Number.isFinite(numeric)){out.push({key:item.key,status:'number_required'});continue;}
                  e.value=String(numeric);
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
            confirmSubmit(values)
        }
    }

    /** API-key-free local form mapping. No farmer data leaves this device. */
    private fun requestAiMapping(raw: String) {
        val modelPath = getSharedPreferences("shraddha_offline_model", MODE_PRIVATE)
            .getString("active_model_path", "").orEmpty()
        if (modelPath.isBlank() || !java.io.File(modelPath).isFile) {
            status.text = "प्राकृतिक कमांड के लिए Offline AI model चाहिए। API Key की जरूरत नहीं; Offline AI model इम्पोर्ट करें। तब तक field=value कमांड स्थानीय रूप से भर सकते हैं।"
            return
        }
        inspectPage { page ->
            val fields = page.optJSONArray("fields") ?: JSONArray()
            val prompt = """You are a strict offline AKKPS form mapper.
Return JSON only: {"assignments":[{"field":"exact visible field label/name/id","value":"literal value explicitly said by the user"}],"missing":["required value not supplied"],"submit":false}.
Map only explicit values from the user's command to visible fields. Do not invent or infer personal data. Use only exact visible fields and select options. Never submit unless the user explicitly asked.
AKKPS page: ${page.optString("title")}
Visible fields: ${fields.toString().take(9000)}
User command: ${raw.take(2500)}
Return JSON only."""
            status.text = "Shraddha का Offline AI फॉर्म समझ रहा है…"
            Thread {
                val response = runCatching { LocalOfflineLlm.generate(this, modelPath, prompt) }
                runOnUiThread {
                    val reply = response.getOrNull()
                    if (reply.isNullOrBlank()) {
                        status.text = "Offline AI ने मान्य उत्तर नहीं दिया। कोई फील्ड नहीं भरी गई।"
                        return@runOnUiThread
                    }
                    val jsonText = reply.substringAfter("{", "").let { "{" + it.substringBeforeLast("}", "") + "}" }
                    val mapped = runCatching { JSONObject(jsonText) }.getOrNull()
                    if (mapped == null) {
                        status.text = "Offline AI का उत्तर पढ़ा नहीं जा सका। कोई फील्ड नहीं भरी गई।"
                        return@runOnUiThread
                    }
                    val items = mapped.optJSONArray("assignments") ?: JSONArray()
                    val values = (0 until items.length()).mapNotNull { i ->
                        val item = items.optJSONObject(i) ?: return@mapNotNull null
                        val key = item.optString("field").trim()
                        val value = item.optString("value").trim()
                        if (key.isBlank() || value.isBlank() || value.length > 500) null else key to value
                    }
                    val missing = mapped.optJSONArray("missing") ?: JSONArray()
                    if (values.isEmpty()) {
                        status.text = if (missing.length() > 0)
                            "जानकारी अधूरी है: " + (0 until missing.length()).joinToString { missing.optString(it) }
                        else "कोई स्पष्ट फील्ड-वैल्यू नहीं मिली। कोई डेटा नहीं भरा गया।"
                        return@runOnUiThread
                    }
                    val explicitSubmit = AkkpsRunnerCommandParser.hasExplicitSubmitIntent(raw)
                    val shouldSubmit = mapped.optBoolean("submit") && explicitSubmit && missing.length() == 0
                    fillValues(values, shouldSubmit)
                    if (missing.length() > 0) {
                        status.text = "Offline AI ने ${values.size} फील्ड पहचाने। अतिरिक्त जानकारी चाहिए: " +
                            (0 until missing.length()).joinToString { missing.optString(it) } + "। Submit रोक दिया गया।"
                    }
                }
            }.start()
        }
    }

    /**
     * Show the exact values that will be sent to AKKPS before any consequential submit.
     * A typed "submit" command expresses intent, but the final on-device review remains required.
     */
    private fun confirmSubmit(values: List<Pair<String, String>>) {
        val preview = values.take(12).joinToString("\n") { (key, value) ->
            "$key: ${value.take(120)}"
        } + if (values.size > 12) "\n…और ${values.size - 12} फील्ड" else ""
        AlertDialog.Builder(this)
            .setTitle("AKKPS रिकॉर्ड की अंतिम जाँच")
            .setMessage("ये मान AKKPS में जमा किए जाएँगे। नाम, गाँव और अन्य जानकारी जाँच लें।\n\n$preview")
            .setNegativeButton("वापस जाँचें", null)
            .setPositiveButton("सही है — Submit करें") { _, _ -> submitForm(values) }
            .show()
    }

    private fun submitForm(expectedValues: List<Pair<String, String>>) {
        val js = """
            (function(){
              const path=(location.pathname||'').toLowerCase();
              const kind=path.includes('awd')?'awd':path.includes('plot')?'plot':path.includes('farmer')?'farmer':'unknown';
              if(kind==='unknown') return JSON.stringify({status:'unsupported_module'});
              const forms=[...document.forms].filter(f=>[...f.querySelectorAll('input,textarea,select')].some(e=>!e.disabled && e.type!=='hidden' && e.type!=='password' && e.value));
              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
              const f=forms[0];
              const labels=[...f.querySelectorAll('input,textarea,select')].map(e=>[e.name,e.id,e.placeholder,...[...(e.labels||[])].map(l=>l.innerText)].join(' ').toLowerCase()).join(' ');
              const markers=kind==='farmer'?['name','phone']:kind==='plot'?['survey','village']:['year','season'];
              if(!markers.some(m=>labels.includes(m))) return JSON.stringify({status:'module_form_mismatch'});
              if(!f.reportValidity()) return JSON.stringify({status:'invalid'});
              const b=f.querySelector('button[type=submit],input[type=submit]');
              if(b)b.click();else f.requestSubmit();
              return JSON.stringify({status:'dispatched'});
            })()
        """.trimIndent()
        val before = web.url.orEmpty()
        web.evaluateJavascript(js) { raw ->
            val statusCode = runCatching { JSONObject(decode(raw)).optString("status") }.getOrDefault("")
            if (statusCode != "dispatched") {
                status.text = when(statusCode) {
                    "invalid" -> "AKKPS validation अधूरी है।"
                    "unsupported_module" -> "यह Farmer, Plot या AWD पेज नहीं है; Submit रोक दिया।"
                    "module_form_mismatch" -> "पेज और फॉर्म का प्रकार मेल नहीं खाता; Submit रोक दिया।"
                    else -> "एक स्पष्ट और सही module form नहीं मिला; Submit नहीं किया।"
                }
                return@evaluateJavascript
            }
            status.text = "Submit request भेजी गई। AKKPS response जाँचा जा रहा है…"
            web.postDelayed({ verifySubmit(before, expectedValues) }, 1800)
        }
    }

    /** A success toast alone is not proof. Find the matching saved row, open it, and compare values. */
    private fun verifySubmit(beforeUrl: String, expectedValues: List<Pair<String, String>>) {
        val expected = JSONArray()
        expectedValues.forEach { (_, value) -> expected.put(value) }
        val js = """
            (function(expected){
              const norm=s=>(s||'').toLowerCase().replace(/\\s+/g,' ').trim();
              const body=norm(document.body?.innerText||'');
              const values=expected.map(norm).filter(Boolean);
              const currentMatches=values.length>0 && values.every(v=>body.includes(v));
              const rows=[...document.querySelectorAll('tr,[role="row"]')];
              const row=rows.find(r=>{const t=norm(r.innerText);return values.length>0&&values.every(v=>t.includes(v));});
              let href='';
              if(row){
                const links=[...row.querySelectorAll('a[href]')];
                const link=links.find(a=>/view|detail|open|record|देखें/i.test((a.innerText||'')+' '+a.href))||links[0];
                if(link){try{const u=new URL(link.href,location.href);if(u.protocol==='https:'&&u.hostname===location.hostname)href=u.href;}catch(e){}}
              }
              return JSON.stringify({currentMatches,rowFound:!!row,href,bodyHasSuccess:/success|submitted|saved successfully|created successfully|record saved|सफलतापूर्वक|जमा हो गया/i.test(body),url:location.href});
            })(__EXPECTED__)
        """.trimIndent().replace("__EXPECTED__", expected.toString())
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result == null) {
                status.text = "AKKPS response पढ़ा नहीं जा सका। रिकॉर्ड सफल नहीं माना गया।"
                return@evaluateJavascript
            }
            val href = result.optString("href")
            when {
                result.optBoolean("currentMatches") && result.optString("url") != beforeUrl ->
                    status.text = "रिकॉर्ड नए पेज पर खुला और दिए गए मान मिल गए। AKKPS रिकॉर्ड सत्यापित है।"
                href.isNotBlank() -> {
                    status.text = "मिलता-जुलता सेव रिकॉर्ड मिला। अब उसे खोलकर मान जाँचे जा रहे हैं…"
                    web.loadUrl(href)
                    web.postDelayed({ verifyOpenedRecord(expectedValues) }, 1800)
                }
                result.optBoolean("rowFound") ->
                    status.text = "सूची में रिकॉर्ड मिला, लेकिन उसे दोबारा खोलने का सुरक्षित लिंक नहीं मिला। पूर्ण सत्यापन बाकी है।"
                result.optBoolean("bodyHasSuccess") ->
                    status.text = "AKKPS सफलता संदेश मिला, लेकिन रिकॉर्ड दोबारा खोलकर मान सत्यापित नहीं हुए। इसे पूर्ण सफलता नहीं माना।"
                result.optString("url") != beforeUrl ->
                    status.text = "AKKPS पेज बदला, लेकिन रिकॉर्ड के मान सत्यापित नहीं हुए।"
                else -> status.text = "सेव रिकॉर्ड का प्रमाण नहीं मिला। Submit सफल नहीं माना गया।"
            }
        }
    }

    private fun verifyOpenedRecord(expectedValues: List<Pair<String, String>>) {
        val expected = JSONArray()
        expectedValues.forEach { (_, value) -> expected.put(value) }
        val js = """
            (function(expected){
              const norm=s=>(s||'').toLowerCase().replace(/\\s+/g,' ').trim();
              const body=norm(document.body?.innerText||'');
              const values=expected.map(norm).filter(Boolean);
              const matched=values.length>0&&values.every(v=>body.includes(v));
              return JSON.stringify({matched,url:location.href,body:body.slice(0,12000)});
            })(__EXPECTED__)
        """.trimIndent().replace("__EXPECTED__", expected.toString())
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = if (result?.optBoolean("matched") == true)
                "AKKPS में सेव रिकॉर्ड दोबारा खुला। सभी दिए गए मान मिल गए; रिकॉर्ड सत्यापित है।"
            else
                "रिकॉर्ड पेज खुला, लेकिन सभी मान नहीं मिले। इसे सत्यापित नहीं माना गया; कृपया AKKPS रिकॉर्ड जाँचें।"
        }
    }

    /** Device speech recognition; no Shraddha API key is used. */
    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "इस फोन में Speech Recognition service उपलब्ध नहीं है।"
            return
        }
        runCatching {
            speechRecognizer?.destroy()
            speechRecognizer = if (Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "बोलिए… Offline recognition उपलब्ध होने पर डिवाइस पर समझा जाएगा।" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "आवाज़ समझी जा रही है…" }
                override fun onError(error: Int) { status.text = "आवाज़ नहीं समझी गई (कोड " + error + ")। दोबारा बोलें।" }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isBlank()) { status.text = "कोई आवाज़ पहचानी नहीं गई।"; return }
                    handleVoiceCommand(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        }.onFailure { status.text = "Voice engine शुरू नहीं हुआ: " + (it.localizedMessage ?: "unknown error") }
    }

    private fun handleVoiceCommand(spoken: String) {
        val value = spoken.trim()
        command.setText(value)
        val normalized = value.lowercase()
        when {
            listOf("बाउंड्री शुरू", "सीमा बनाओ", "मैप पर सीमा", "boundary start").any(normalized::contains) -> startBoundaryDraft()
            listOf("क्षेत्रफल निकाल", "एरिया निकाल", "area calculate").any(normalized::contains) -> calculateBoundaryArea()
            listOf("बाउंड्री हटाओ", "सीमा साफ", "clear boundary").any(normalized::contains) -> clearBoundaryDraft()
            listOf("मैप देखो", "स्क्रीन देखो", "आँख से देख").any(normalized::contains) -> inspectVisualScreen()
            else -> executeCommand()
        }
    }

    /** On-device OCR reads the AKKPS WebView; screenshots are never uploaded. */
    private fun inspectVisualScreen() {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "पहले AKKPS का सुरक्षित पेज खोलें。"; return
        }
        web.post {
            if (web.width <= 0 || web.height <= 0) { status.text = "मैप स्क्रीन अभी तैयार नहीं है।"; return@post }
            val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bitmap))
            val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            val image = InputImage.fromBitmap(bitmap, 0)
            val latinTask = latin.process(image)
            val devanagariTask = devanagari.process(image)
            com.google.android.gms.tasks.Tasks.whenAllComplete(latinTask, devanagariTask)
                .addOnSuccessListener {
                    val recognized = listOf(latinTask.result?.text, devanagariTask.result?.text)
                        .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" | ")
                    status.text = if (recognized.isBlank()) "स्क्रीन डिवाइस पर पढ़ी गई, लेकिन स्पष्ट टेक्स्ट नहीं मिला।"
                        else "डिवाइस पर स्क्रीन पढ़ी गई: " + recognized.take(650)
                    bitmap.recycle(); latin.close(); devanagari.close()
                }
                .addOnFailureListener {
                    bitmap.recycle(); latin.close(); devanagari.close()
                    status.text = "Offline screen OCR असफल: " + (it.localizedMessage ?: "unknown error")
                }
        }
    }

    /**
     * Reuses the map already rendered by AKKPS; no tile provider or map API key.
     * Map taps are a correction/fallback only when the page exposes its map object.
     */
    private fun startBoundaryDraft() {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS का मैप पेज खोलें।"; return
        }
        val js = """
            (function(){
              const globals=Object.keys(window).filter(k=>/map/i.test(k)).map(k=>({name:k,value:window[k]}));
              const leaflet=globals.find(x=>x.value&&x.value._container&&typeof x.value.on==='function'&&typeof x.value.getCenter==='function');
              const google=globals.find(x=>x.value&&x.value.__gm&&typeof x.value.getCenter==='function');
              const mapbox=globals.find(x=>x.value&&typeof x.value.addSource==='function'&&typeof x.value.on==='function');
              const map=leaflet?.value||google?.value||mapbox?.value;
              if(!map) return JSON.stringify({ok:false});
              window.__shraddhaBoundaryPoints=[];
              window.__shraddhaBoundaryLayer=null;
              const addPoint=(lat,lng)=>{
                window.__shraddhaBoundaryPoints.push({latitude:Number(lat),longitude:Number(lng)});
                const pts=window.__shraddhaBoundaryPoints;
                if(leaflet&&pts.length>=3){
                  if(!window.__shraddhaBoundaryLayer) window.__shraddhaBoundaryLayer=window.L.polygon(pts.map(p=>[p.latitude,p.longitude]),{color:'#00e5ff',weight:3,fillOpacity:.18}).addTo(map);
                  else window.__shraddhaBoundaryLayer.setLatLngs(pts.map(p=>[p.latitude,p.longitude]));
                } else if(google&&window.google?.maps?.Polygon&&pts.length>=3){
                  if(!window.__shraddhaBoundaryLayer) window.__shraddhaBoundaryLayer=new google.maps.Polygon({map,strokeColor:'#00e5ff',strokeWeight:3,fillColor:'#00e5ff',fillOpacity:.18});
                  window.__shraddhaBoundaryLayer.setPath(pts.map(p=>({lat:p.latitude,lng:p.longitude})));
                } else if(mapbox&&pts.length>=3){
                  const ring=pts.map(p=>[p.longitude,p.latitude]);ring.push(ring[0]);
                  const data={type:'Feature',geometry:{type:'Polygon',coordinates:[ring]},properties:{}};
                  if(!map.getSource('shraddha-boundary')){
                    map.addSource('shraddha-boundary',{type:'geojson',data});
                    map.addLayer({id:'shraddha-boundary-fill',type:'fill',source:'shraddha-boundary',paint:{'fill-color':'#00e5ff','fill-opacity':.18}});
                    map.addLayer({id:'shraddha-boundary-line',type:'line',source:'shraddha-boundary',paint:{'line-color':'#00e5ff','line-width':3}});
                  } else map.getSource('shraddha-boundary').setData(data);
                }
              };
              if(leaflet)map.on('click',e=>addPoint(e.latlng.lat,e.latlng.lng));
              else if(google)map.addListener('click',e=>addPoint(e.latLng.lat(),e.latLng.lng()));
              else if(mapbox)map.on('click',e=>addPoint(e.lngLat.lat,e.lngLat.lng));
              return JSON.stringify({ok:true,engine:leaflet?'leaflet':google?'google-maps':'mapbox-gl'});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = if (result?.optBoolean("ok") == true)
                "AKKPS मैप से जुड़ गया। खेत के कोने मैप पर टैप करें; फिर 'क्षेत्रफल निकालें' बोलें।"
            else "AKKPS मैप का अंदरूनी object उपलब्ध नहीं है। स्क्रीन OCR उपलब्ध है, पर polygon auto-draw अभी समर्थित नहीं।"
        }
    }

    private fun calculateBoundaryArea() {
        web.evaluateJavascript("(window.__shraddhaBoundaryPoints||[]).map(p=>JSON.stringify(p)).join('\\\\n')") { raw ->
            val points = decode(raw).lineSequence().mapNotNull { line ->
                runCatching { JSONObject(line).let { AkkpsGeoPoint(it.getDouble("latitude"), it.getDouble("longitude")) } }.getOrNull()
            }.toList()
            val measurement = runCatching { AkkpsBoundaryGeometry.measure(points) }.getOrNull()
            if (measurement == null) {
                status.text = "कम-से-कम 3 अलग सही मैप पॉइंट लगाएँ; क्षेत्रफल नहीं निकाला गया।"
            } else {
                status.text = String.format(java.util.Locale.ROOT,
                    "बाउंड्री: %.2f m² • %.4f hectare • %.4f acre. पुष्टि के बाद ही AKKPS में सेव करें।",
                    measurement.squareMeters, measurement.hectares, measurement.acres)
            }
        }
    }

    private fun clearBoundaryDraft() {
        web.evaluateJavascript("""
            (function(){
              const layer=window.__shraddhaBoundaryLayer;
              if(layer&&layer.remove)layer.remove();
              if(layer&&layer.setMap)layer.setMap(null);
              window.__shraddhaBoundaryLayer=null;
              window.__shraddhaBoundaryPoints=[];
              return true;
            })()
        """.trimIndent(), null)
        status.text = "अस्थायी बाउंड्री हटाई गई। AKKPS रिकॉर्ड में कुछ सेव नहीं किया गया।"
    }

    private fun decode(raw: String?): String =
        runCatching { JSONTokener(raw ?: "null").nextValue() as? String ?: "" }.getOrDefault("")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==REQUEST_FILE) {
            val selected = if (resultCode == RESULT_OK) {
                val uris = linkedSetOf<Uri>()
                if (uploadAllowsMultiple) {
                    data?.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                    }
                }
                data?.data?.let { uris += it }
                uris.takeIf { it.isNotEmpty() }?.toTypedArray()
            } else null
            uploadCallback?.onReceiveValue(selected)
            uploadCallback = null
            uploadAllowsMultiple = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceCommand()
        else if (requestCode == REQUEST_MIC) status.text = "आवाज़ के लिए Microphone अनुमति आवश्यक है।"
    }

    override fun onDestroy() {
        speechRecognizer?.destroy(); speechRecognizer = null
        uploadCallback?.onReceiveValue(null); uploadCallback=null
        web.stopLoading(); web.destroy(); super.onDestroy()
    }

    companion object { private const val REQUEST_FILE=7314; private const val REQUEST_MIC=7315 }
}

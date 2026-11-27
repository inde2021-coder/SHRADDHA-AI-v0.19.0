package `in`.shraddha.ai.operator.website

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
    private var pendingGeolocationCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private lateinit var plotIdInput: EditText
    private val boundaryPoints = mutableListOf<AkkpsGeoPoint>()
    private var locationManager: LocationManager? = null
    private var gpsWalkActive = false
    private var lastGpsAccuracyMeters = Float.MAX_VALUE
    private var bestGpsAccuracyMeters = Float.MAX_VALUE
    private var lastGpsElapsedRealtimeNanos = 0L
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!gpsWalkActive || !location.hasAccuracy()) return
            val previous = boundaryPoints.lastOrNull()
            val point = AkkpsGeoPoint(location.latitude, location.longitude)
            val distance = previous?.let {
                val results = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, point.latitude, point.longitude, results)
                results[0].toDouble()
            }
            val elapsed = if (previous != null && lastGpsElapsedRealtimeNanos > 0L) {
                (location.elapsedRealtimeNanos - lastGpsElapsedRealtimeNanos) / 1_000_000_000.0
            } else null
            when (AkkpsGpsFixPolicy.evaluate(location.accuracy, distance, elapsed)) {
                AkkpsGpsFixPolicy.Decision.INACCURATE -> {
                    status.text = "GPS signal कमजोर (±${location.accuracy.toInt()} m)। Point नहीं लिया; बेहतर signal की प्रतीक्षा करें।"
                    return
                }
                AkkpsGpsFixPolicy.Decision.TOO_CLOSE -> return
                AkkpsGpsFixPolicy.Decision.IMPLAUSIBLE_JUMP -> {
                    status.text = "GPS jump मिला। संदिग्ध point छोड़ दिया; Walk जारी है।"
                    return
                }
                AkkpsGpsFixPolicy.Decision.INVALID_TIME -> {
                    status.text = "GPS timestamp असंगत है। Point छोड़ दिया; अगला fix लिया जाएगा।"
                    return
                }
                AkkpsGpsFixPolicy.Decision.ACCEPT -> Unit
            }
            if (boundaryPoints.size >= AkkpsBoundaryGeometry.MAX_POINTS) {
                stopGpsWalk()
                status.text = "500 GPS points पूरे हो गए। Walk रोक दी गई; boundary save करें।"
                return
            }
            boundaryPoints.add(point)
            lastGpsAccuracyMeters = location.accuracy
            bestGpsAccuracyMeters = minOf(bestGpsAccuracyMeters, location.accuracy)
            lastGpsElapsedRealtimeNanos = location.elapsedRealtimeNanos
            status.text = "GPS Walk: ${boundaryPoints.size} points • Current ±${location.accuracy.toInt()} m • Best ±${bestGpsAccuracyMeters.toInt()} m"
        }
        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, statusValue: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                stopGpsWalk()
                status.text = "GPS बंद है। Location चालू करके Walk फिर शुरू करें।"
            }
        }
    }

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
        plotIdInput = EditText(this).apply {
            hint = "AKKPS Plot ID (जैसे PLOT-123)"
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(0xFF142536.toInt())
        }
        root.addView(plotIdInput, LinearLayout.LayoutParams(-1, dp(46)))
        val boundaryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boundaryActions.addView(action("GPS Walk शुरू") { startGpsWalk() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        boundaryActions.addView(action("Walk रोकें") { stopGpsWalk() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        boundaryActions.addView(action("Save Boundary") { saveBoundary() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        boundaryActions.addView(action("Reopen Saved") { reopenBoundary() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(boundaryActions)
        val exportActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportActions.addView(action("KML Import") { importBoundaryKml() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        exportActions.addView(action("KML Export") { exportBoundaryKml() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        exportActions.addView(action("नई Walk") { boundaryPoints.clear(); status.text = "नई boundary walk के लिए तैयार।" }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(exportActions)
        val candidateActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candidateActions.addView(action("Satellite Candidate Import") { importSatelliteCandidate() },
            LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(candidateActions)
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
            settings.setGeolocationEnabled(true)
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
                    if (AkkpsWebsiteProfile.acceptsUrl(url)) {
                        status.text = "AKKPS पेज खुला। Login के बाद Inspect दबाएँ।"
                        installSubmitObserver()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                    if (!AkkpsWebsiteProfile.acceptsUrl(origin)) {
                        callback.invoke(origin, false, false)
                        return
                    }
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        confirmAkkpsLocation(origin, callback)
                    } else {
                        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin.orEmpty(), false, false)
                        pendingGeolocationCallback = callback
                        pendingGeolocationOrigin = origin
                        requestPermissions(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            REQUEST_LOCATION
                        )
                    }
                }

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

    private fun startGpsWalk() {
        if (gpsWalkActive) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_GPS_WALK)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            status.text = "GPS चालू करें। खेत के किनारे से Walk करें; फसल के अंदर जाने की जरूरत नहीं।"
            return
        }
        boundaryPoints.clear()
        lastGpsAccuracyMeters = Float.MAX_VALUE
        bestGpsAccuracyMeters = Float.MAX_VALUE
        lastGpsElapsedRealtimeNanos = 0L
        locationManager = manager
        try {
            gpsWalkActive = true
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, gpsListener, Looper.getMainLooper())
            status.text = "GPS Walk चालू। खेत की बाहरी मेड़ के किनारे धीरे-धीरे चलें।"
        } catch (_: SecurityException) {
            gpsWalkActive = false
            status.text = "Precise location अनुमति नहीं मिली।"
        } catch (e: Exception) {
            gpsWalkActive = false
            status.text = "GPS Walk शुरू नहीं हुई: ${e.localizedMessage ?: "GPS unavailable"}"
        }
    }

    private fun stopGpsWalk() {
        gpsWalkActive = false
        runCatching { locationManager?.removeUpdates(gpsListener) }
        locationManager = null
        status.text = "GPS Walk रुकी: ${boundaryPoints.size} points • Accuracy आखिरी ±${if (lastGpsAccuracyMeters.isFinite()) lastGpsAccuracyMeters.toInt() else 0} m"
    }

    private fun saveBoundary() {
        val plotId = plotIdInput.text.toString().trim()
        if (plotId.isBlank()) { status.text = "पहले AKKPS Plot ID भरें।"; return }
        if (gpsWalkActive) { status.text = "पहले GPS Walk रोकें, फिर Save करें।"; return }
        if (boundaryPoints.size < 3) { status.text = "Save करने के लिए पहले कम-से-कम 3 GPS points रिकॉर्ड करें या KML Import करें।"; return }
        val boundary = AkkpsBoundary(boundaryPoints.toList())
        val issue = AkkpsBoundaryGeometry.validate(boundary.points)
        if (issue != null) { status.text = issue; return }
        AlertDialog.Builder(this)
            .setTitle("Boundary Save करें?")
            .setMessage("Plot: $plotId\nPoints: ${boundary.points.size}\nArea: ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres\n\nयह boundary इस फोन में encrypted local storage में save होगी। AKKPS server पर upload नहीं होगी।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("Local Save") { _, _ ->
                runCatching { AkkpsBoundaryStore(this).save(plotId, boundary) }
                    .onSuccess { status.text = "Boundary locally save हुई। ${boundary.points.size} points • ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres" }
                    .onFailure { status.text = "Save failed: ${it.localizedMessage}" }
            }.show()
    }

    private fun reopenBoundary() {
        val plotId = plotIdInput.text.toString().trim()
        if (plotId.isBlank()) { status.text = "पहले वही AKKPS Plot ID भरें।"; return }
        if (gpsWalkActive) { status.text = "Saved boundary खोलने से पहले GPS Walk रोकें।"; return }
        runCatching { AkkpsBoundaryStore(this).load(plotId) }
            .onSuccess { saved ->
                if (saved == null) status.text = "इस Plot ID की कोई saved local boundary नहीं मिली।"
                else {
                    boundaryPoints.clear()
                    boundaryPoints.addAll(saved.points)
                    status.text = "Boundary reopen हुई: ${saved.points.size} points • ${"%.3f".format(java.util.Locale.US, saved.areaAcres)} acres। Plot ID: $plotId"
                }
            }
            .onFailure { status.text = "Saved boundary पढ़ी नहीं जा सकी: ${it.localizedMessage}" }
    }

    private fun importBoundaryKml() {
        if (gpsWalkActive) { status.text = "KML Import से पहले GPS Walk रोकें।"; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.google-earth.kml+xml"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_KML_IMPORT)
    }

    private fun importSatelliteCandidate() {
        if (gpsWalkActive) { status.text = "Candidate Import से पहले GPS Walk रोकें।"; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/geo+json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_SATELLITE_CANDIDATE)
    }

    private fun exportBoundaryKml() {
        if (gpsWalkActive) { status.text = "KML Export से पहले GPS Walk रोकें।"; return }
        val plotId = plotIdInput.text.toString().trim()
        val boundary = runCatching {
            if (boundaryPoints.size >= 3) AkkpsBoundary(boundaryPoints.toList())
            else AkkpsBoundaryStore(this).load(plotId) ?: error("पहले boundary record करें या reopen करें।")
        }.getOrElse { status.text = it.localizedMessage ?: "Boundary उपलब्ध नहीं।"; return }
        val issue = AkkpsBoundaryGeometry.validate(boundary.points)
        if (issue != null) { status.text = issue; return }
        pendingKml = AkkpsBoundaryGeometry.toKml(boundary, plotId.ifBlank { "AKKPS Plot" })
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_TITLE, "${plotId.ifBlank { "akkps-boundary" }}.kml")
        }, REQUEST_KML_EXPORT)
    }

    private var pendingKml: String? = null

    private fun confirmAkkpsLocation(
        origin: String,
        callback: android.webkit.GeolocationPermissions.Callback
    ) {
        if (!AkkpsWebsiteProfile.acceptsUrl(origin)) {
            callback.invoke(origin, false, false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("AKKPS GPS Walk")
            .setMessage("AKKPS को खेत की boundary walk के दौरान इस फोन की precise location चाहिए। Location केवल इस वेबसाइट request के लिए दी जाएगी; background location नहीं माँगी जाएगी।")
            .setPositiveButton("इस बार अनुमति दें") { _, _ -> callback.invoke(origin, true, false) }
            .setNegativeButton("अनुमति न दें") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_GPS_WALK) {
            if (grantResults.isNotEmpty() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGpsWalk()
            else status.text = "GPS Walk के लिए Precise location जरूरी है; अनुमति नहीं मिली।"
            return
        }
        if (requestCode != REQUEST_LOCATION) return
        val origin = pendingGeolocationOrigin.orEmpty()
        val callback = pendingGeolocationCallback
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
        if (callback == null) return
        if (AkkpsWebsiteProfile.acceptsUrl(origin) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            confirmAkkpsLocation(origin, callback)
        } else {
            callback.invoke(origin, false, false)
            status.text = "GPS Walk के लिए Precise location अनुमति नहीं मिली; Draw mode अभी भी उपलब्ध है।"
        }
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setOnClickListener { click() }
    }

    /** Capture same-origin write responses without storing request bodies, tokens or personal data. */
    private fun installSubmitObserver() {
        val js = """
            (function(){
              if(window.__akkpsSubmitObserverInstalled) return true;
              window.__akkpsSubmitObserverInstalled=true;
              window.__akkpsSubmitEvents=[];
              window.__akkpsSubmitSequence=0;
              const record=(method,url,status,body)=>{
                try {
                  const parsed=typeof body==='string'?JSON.parse(body):body;
                  const path=new URL(url,location.href).pathname;
                  const id=parsed?.recordId||parsed?.plotId||parsed?.reportId||parsed?.data?.recordId||parsed?.data?.plotId||parsed?.data?.reportId||'';
                  const state=String(parsed?.status||'').toLowerCase();
                  const message=String(parsed?.message||parsed?.error||'').slice(0,160);
                  const recordWrite=/\\b(plot|plots|farmer|farmers|awd|report|records|boundary)\\b/i.test(path);
                  const success=recordWrite&&(parsed?.success===true||parsed?.ok===true||['success','created','saved','ok'].includes(state)||/saved successfully|submitted successfully|record created/i.test(message));
                  window.__akkpsSubmitEvents.push({
                    sequence:++window.__akkpsSubmitSequence,
                    method:String(method||'').toUpperCase(),
                    path,
                    recordWrite,
                    httpStatus:Number(status)||0,
                    success:!!success,
                    recordId:String(id||'').slice(0,100),
                    message,
                    at:Date.now()
                  });
                  if(window.__akkpsSubmitEvents.length>20) window.__akkpsSubmitEvents.shift();
                } catch(_){}
              };
              // AKKPS may serve its authenticated API from an official subdomain.
              // Observe only HTTPS endpoints on the exact akkps.ngo domain tree;
              // never inspect unrelated third-party analytics/payment origins.
              const isAkkpsEndpoint=url=>{
                try{
                  const u=new URL(url,location.href),host=u.hostname.toLowerCase();
                  return u.protocol==='https:'&&(host==='akkps.ngo'||host.endsWith('.akkps.ngo'));
                }catch(_){return false}
              };
              const originalFetch=window.fetch;
              if(originalFetch) window.fetch=function(input,init){
                const url=typeof input==='string'?input:(input?.url||'');
                const method=String(init?.method||input?.method||'GET').toUpperCase();
                if(!isAkkpsEndpoint(url)||!['POST','PUT','PATCH'].includes(method))
                  return originalFetch.apply(this,arguments);
                return originalFetch.apply(this,arguments).then(response=>{
                  response.clone().text().then(body=>record(method,url,response.status,body)).catch(()=>{});
                  return response;
                });
              };
              const originalOpen=XMLHttpRequest.prototype.open;
              const originalSend=XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open=function(method,url){
                this.__akkpsMethod=String(method||'GET').toUpperCase();
                this.__akkpsUrl=String(url||'');
                return originalOpen.apply(this,arguments);
              };
              XMLHttpRequest.prototype.send=function(){
                try{
                  const xhr=this;
                  const endpoint=new URL(xhr.__akkpsUrl,location.href);
                  const host=endpoint.hostname.toLowerCase();
                  const akkpsEndpoint=endpoint.protocol==='https:'&&(host==='akkps.ngo'||host.endsWith('.akkps.ngo'));
                  if(akkpsEndpoint&&['POST','PUT','PATCH'].includes(xhr.__akkpsMethod)){
                    xhr.addEventListener('load',()=>record(xhr.__akkpsMethod,xhr.__akkpsUrl,xhr.status,xhr.responseText),{once:true});
                  }
                }catch(_){}
                return originalSend.apply(this,arguments);
              };
              return true;
            })()
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun inspectPage(onReady: ((JSONObject) -> Unit)? = null) {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS के सुरक्षित पेज पर जाएँ।"; return
        }
        // Capture page structure only. Never read input values, cookies, passwords, OTPs or farmer records.
        val js = """
            (function(){
              const n=s=>(s||'').replace(/\s+/g,' ').trim();
              const visible=e=>!!(e && (e.offsetWidth||e.offsetHeight||e.getClientRects().length));
              const fields=[...document.querySelectorAll('input,textarea,select')].map((e,i)=>{
                const labels=[...(e.labels||[])].map(x=>n(x.innerText)).filter(Boolean);
                const p=e.closest('label'); if(p) labels.push(n(p.innerText));
                return {index:i,tag:e.tagName.toLowerCase(),type:(e.type||'').toLowerCase(),
                  name:e.name||'',id:e.id||'',placeholder:e.placeholder||'',
                  aria:e.getAttribute('aria-label')||'',label:[...new Set(labels)].join(' / '),
                  required:!!e.required,disabled:!!e.disabled};
              }).filter(x=>x.type!=='hidden' && x.type!=='password' && x.type!=='file' && !x.disabled);
              const modules=[...document.querySelectorAll('nav a[href],[role=navigation] a[href],header a[href],aside a[href]')].filter(visible).map(a=>{
                try { const u=new URL(a.href,location.href); if(u.origin!==location.origin)return null;
                  return {label:n(a.innerText||a.getAttribute('aria-label')||a.title).slice(0,100),
                    path:u.pathname,active:a.getAttribute('aria-current')==='page'||a.classList.contains('active')};
                } catch(_){return null;}
              }).filter(x=>x && x.label && x.path && !x.path.startsWith('/admin') && !x.path.startsWith('/coordinator'))
                .filter((x,i,a)=>a.findIndex(y=>y.path===x.path&&y.label===x.label)===i).slice(0,80);
              const actions=[...document.querySelectorAll('form button,form input[type=submit],form input[type=button],form [role=button]')]
                .filter(visible).map(e=>({label:n(e.innerText||e.value||e.getAttribute('aria-label')||e.title).slice(0,100),
                  type:(e.type||e.tagName||'').toLowerCase(),disabled:!!e.disabled}))
                .filter(x=>x.label).slice(0,80);
              const forms=[...document.forms].filter(visible).map((f,i)=>({
                index:i,method:(f.method||'get').toLowerCase(),
                path:(()=>{try{return new URL(f.action||location.href,location.href).pathname}catch(_){return location.pathname}})(),
                fieldCount:f.querySelectorAll('input:not([type=hidden]),textarea,select').length
              })).slice(0,30);
              const tables=[...document.querySelectorAll('table')].filter(visible).map((t,i)=>({
                index:i,headers:[...t.querySelectorAll('thead th, thead td')].map(x=>n(x.innerText)).filter(Boolean).slice(0,30),
                rowCount:t.querySelectorAll('tbody tr').length
              })).slice(0,20);
              return JSON.stringify({title:n(document.title),url:location.href,path:location.pathname,fields,modules,actions,forms,tables});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val obj = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (obj == null) { status.text = "पेज की जानकारी नहीं पढ़ी जा सकी।"; return@evaluateJavascript }
            AkkpsPageInventory.save(this, obj)
            status.text = AkkpsPageInventory.summary(obj)
            onReady?.invoke(obj)
        }
    }

    private fun executeCommand() {
        val raw = command.text.toString().trim()
        if (raw.isBlank() || !AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS में Login करें और field=value कमांड दें।"; return
        }
        val parsed = AkkpsRunnerCommandParser.parse(raw)
        val explicitAssignment = raw.contains('=') || raw.contains(':') || parsed.submit
        if (!explicitAssignment) {
            requestAiMapping(raw)
            return
        }
        if (parsed.issues.isNotEmpty()) {
            status.text = parsed.issues.joinToString("\\n")
            return
        }
        val wantsSubmit = parsed.submit
        val values = parsed.assignments
        if (values.isEmpty()) {
            status.text = "Submit से पहले कम-से-कम एक स्पष्ट field=value assignment दें।"
            return
        }
        fillValues(values, wantsSubmit)
    }

    private fun fillValues(values: List<Pair<String, String>>, wantsSubmit: Boolean, blockedSubmitReason: String? = null) {
        val payload = JSONArray()
        values.forEach { payload.put(JSONObject().put("key", it.first).put("value", it.second)) }
        val allowedInputTypes = JSONArray(AkkpsRunnerFieldPolicy.allowedInputTypes.toList()).toString()
        val allowSingleSelect = AkkpsRunnerFieldPolicy.isAllowedSelect(multiple = false, disabled = false)
        val js = """
            (function(items){
              const norm=s=>String(s||'').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
              const all=[...document.querySelectorAll('input,textarea,select')],out=[];
              const visible=e=>!!(e && (e.offsetWidth||e.offsetHeight||e.getClientRects().length) &&
                getComputedStyle(e).display!=='none' && getComputedStyle(e).visibility!=='hidden' &&
                !e.closest('[aria-hidden="true"]'));
              const allowed=new Set(__ALLOWED_INPUT_TYPES__);
              const sensitive=e=>/password|one.?time|otp|cvv|security.?code/i.test(
                [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),e.autocomplete].join(' '));
              const eligible=e=>{
                if(!visible(e)||e.disabled||e.readOnly) return false;
                if(e.tagName==='SELECT') return __ALLOW_SINGLE_SELECT__ && !e.multiple;
                if(e.tagName==='TEXTAREA') return true;
                if(e.tagName!=='INPUT') return false;
                const type=(e.type||'text').toLowerCase();
                return allowed.has(type)&&!sensitive(e);
              };
              for(const item of items){
                const key=norm(item.key);
                const found=all.filter(e=>{
                  if(!eligible(e)) return false;
                  const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...ls]
                    .map(norm).some(v=>v && v===key);
                });
                if(found.length!==1){out.push({key:item.key,status:found.length?'ambiguous':'not_found'});continue;}
                const e=found[0];
                if(e.tagName==='SELECT'){
                  const o=[...e.options].find(x=>!x.disabled&&(norm(x.text)===norm(item.value)||norm(x.value)===norm(item.value)));
                  if(!o){out.push({key:item.key,status:'option_not_found'});continue;} e.value=o.value;
                } else e.value=item.value;
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                out.push({key:item.key,status:e.value===String(item.value)||e.tagName==='SELECT'?'filled':'rejected'});
              }
              const missing=[...document.querySelectorAll('input[required],textarea[required],select[required]')]
                .filter(e=>visible(e)&&!e.disabled&&!e.value&&e.type!=='file')
                .map(e=>e.name||e.id||e.placeholder||'required field');
              return JSON.stringify({out,missing});
            })(__PAYLOAD__)
        """.trimIndent()
            .replace("__PAYLOAD__", payload.toString())
            .replace("__ALLOWED_INPUT_TYPES__", allowedInputTypes)
            .replace("__ALLOW_SINGLE_SELECT__", allowSingleSelect.toString())
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
                status.text = blockedSubmitReason ?: (out.length().toString() + " फील्ड भरे गए। Submit के लिए कमांड के अंत में submit लिखें।")
                return@evaluateJavascript
            }
            val missing = result.optJSONArray("missing") ?: JSONArray()
            if (missing.length() > 0) {
                status.text = "आवश्यक फील्ड खाली हैं: " +
                    (0 until missing.length()).joinToString { missing.optString(it) } + "। Submit नहीं किया।"
                return@evaluateJavascript
            }
            AlertDialog.Builder(this)
                .setTitle("AKKPS रिकॉर्ड Submit करें?")
                .setMessage("${out.length()} fields भरी गई हैं। AKKPS form एक बार देख लें। आपकी स्पष्ट अनुमति के बाद ही Submit होगा।")
                .setNegativeButton("अभी नहीं", null)
                .setPositiveButton("Confirm Submit") { _, _ -> submitForm(values) }
                .show()
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
                    if (values.size != items.length() || values.any { (_, value) -> !raw.contains(value, ignoreCase = true) }) {
                        status.text = "AI ने ऐसा मान दिया जो आपके बोले/लिखे command में मौजूद नहीं है। कोई field नहीं भरी गई। जानकारी स्पष्ट करके दोबारा बोलें।"
                        return@runOnUiThread
                    }
                    if (values.isEmpty()) {
                        val missing = mapped.optJSONArray("missing") ?: JSONArray()
                        status.text = if (missing.length() > 0)
                            "जानकारी अधूरी है: " + (0 until missing.length()).joinToString { missing.optString(it) }
                        else "कमांड से कोई स्पष्ट फॉर्म मान नहीं मिला। कोई डेटा नहीं भरा गया।"
                        return@runOnUiThread
                    }
                    val missing = mapped.optJSONArray("missing") ?: JSONArray()
                    val missingItems = (0 until missing.length()).map { missing.optString(it).trim() }
                    if (AkkpsAiSubmitPolicy.hasDuplicateFields(values.map { it.first })) {
                        status.text = "AI mapping में एक ही फील्ड की duplicate entry मिली। कोई डेटा नहीं भरा गया।"
                        return@runOnUiThread
                    }
                    if (values.isNotEmpty() && !AkkpsAiMappingPolicy.areValuesExplicitInCommand(raw, values)) {
                        status.text = "AI mapping में ऐसे values मिले जो आपके command में स्पष्ट रूप से नहीं थे। कोई data नहीं भरा गया।"
                        return@runOnUiThread
                    }
                    val explicitSubmit = Regex("submit|सबमिट|जमा|भेजो|भेज दें|जमा करें", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                    val shouldSubmit = AkkpsAiSubmitPolicy.maySubmit(
                        aiRequested = mapped.optBoolean("submit"),
                        userExplicitlyRequested = explicitSubmit,
                        missing = missingItems
                    )
                    val blockedReason = if (missingItems.any { it.isNotBlank() }) {
                        "AI ने " + values.size + " फील्ड पहचाने। अतिरिक्त जानकारी चाहिए: " +
                            missingItems.filter { it.isNotBlank() }.joinToString() + "। Submit रोका गया।"
                    } else null
                    fillValues(values, shouldSubmit, blockedReason)
                }
            }.start()
        }
    }

    private fun submitForm(expectedValues: List<Pair<String, String>>) {
        val payload = JSONArray().apply { expectedValues.forEach { put(JSONObject().put("key", it.first).put("value", it.second)) } }
        val js = """
            (function(expected){
              const eventStart=window.__akkpsSubmitSequence||0;
              const forms=[...document.forms].filter(f=>[...f.querySelectorAll('input,textarea,select')].some(e=>!e.disabled && e.type!=='hidden' && e.type!=='password' && e.value));
              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
              const f=forms[0]; if(!f.reportValidity()) return JSON.stringify({status:'invalid'});
              for(const item of expected){
                const key=(item.key||'').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\\p{L}\\p{N}]+/gu,' ').trim();
                const matches=[...f.querySelectorAll('input,textarea,select')].filter(e=>{
                  const labels=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) labels.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...labels]
                    .map(x=>(x||'').toLowerCase().replace(/[^\\p{L}\\p{N}]+/gu,' ').trim()).some(x=>x && x===key);
                });
                if(matches.length!==1 || String(matches[0].value)!==String(item.value)) return JSON.stringify({status:'field_changed',field:item.key});
              }
              const b=f.querySelector('button[type=submit],input[type=submit]');
              if(b)b.click();else f.requestSubmit();
              return JSON.stringify({status:'dispatched',eventStart});
            })(__EXPECTED__)
        """.trimIndent().replace("__EXPECTED__", payload.toString())
        val before = web.url.orEmpty()
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            val code = result?.optString("status").orEmpty()
            if (code != "dispatched") {
                status.text = when (code) {
                    "invalid" -> "AKKPS validation अधूरी है।"
                    "field_changed" -> "Submit से पहले field बदल गई; दोबारा जाँचें।"
                    else -> "एक स्पष्ट form नहीं मिला; Submit नहीं किया।"
                }
                return@evaluateJavascript
            }
            status.text = "Submit request भेजी गई। AKKPS की प्रतिक्रिया जाँची जा रही है…"
            val eventStart = result?.optInt("eventStart") ?: 0
            web.postDelayed({ verifySubmit(before, expectedValues, eventStart) }, 3000)
        }
    }

    private fun verifySubmit(beforeUrl: String, expectedValues: List<Pair<String, String>>, eventStart: Int) {
        val expected = JSONArray().apply { expectedValues.forEach { put(it.second) } }
        val js = """
            (function(values){
              const events=(window.__akkpsSubmitEvents||[]).filter(e=>e.sequence>Math.max(0,__EVENT_START__));
              const serverRecord=[...events].reverse().find(e=>e.recordWrite&&['POST','PUT','PATCH'].includes(e.method)&&e.httpStatus>=200&&e.httpStatus<300&&e.success&&e.recordId);
              const serverFailure=events.some(e=>e.recordWrite&&['POST','PUT','PATCH'].includes(e.method)&&(e.httpStatus>=400||/failed|error|invalid/i.test(e.message||'')));
              const selectors='[role="alert"],[aria-live="polite"],[aria-live="assertive"],.alert-success,.toast,[data-sonner-toast]';
              const notices=[...document.querySelectorAll(selectors)].map(e=>(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim()).filter(Boolean);
              const success=/\\b(saved|submitted|created successfully|record saved)\\b|सफलतापूर्वक|जमा हो गया/i;
              const failure=/\\b(failed|error|invalid|unable to|try again)\\b|त्रुटि|असफल/i;
              const notice=notices.find(x=>success.test(x))||'';
              const failed=notices.some(x=>failure.test(x));
              const body=(document.body?.innerText||'').replace(/\\s+/g,' ').trim();
              const identity=values.filter(v=>v && v.length>=3).every(v=>body.includes(v));
              const recordLink=[...document.querySelectorAll('a[href]')].some(a=>/\\/(plots|awd-reports)\\/[^/]+/i.test(a.getAttribute('href')||''));
              return JSON.stringify({notice,failed,identity,recordLink,serverSuccess:!!serverRecord,recordId:serverRecord?.recordId||'',serverPath:serverRecord?.path||'',serverFailure});
            })(__VALUES__)
        """.trimIndent().replace("__EVENT_START__", eventStart.toString()).replace("__VALUES__", expected.toString())
        web.evaluateJavascript(js) { raw ->
            val o = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = when {
                o?.optBoolean("serverFailure") == true || o?.optBoolean("failed") == true -> "AKKPS API/UI ने error दिया। Record saved नहीं माना गया।"
                o?.optBoolean("serverSuccess") == true ->
                    "AKKPS server ने success response और Record ID ${o.optString("recordId")} दिया। Server response verified।"
                !o?.optString("notice").isNullOrBlank() && o?.optBoolean("identity") == true ->
                    "AKKPS UI success notice और record values दिखे, लेकिन server Record ID नहीं मिला। Save अभी fully verified नहीं है।"
                o?.optBoolean("recordLink") == true && o?.optBoolean("identity") == true ->
                    "Plot/AWD detail link और record values मिले। Record खोलकर ID जाँचें।"
                web.url.orEmpty() != beforeUrl ->
                    "AKKPS ने पेज बदला, लेकिन स्पष्ट record confirmation नहीं मिला। Submit verified नहीं है।"
                else -> "स्पष्ट Save/Submit पुष्टि नहीं मिली। दोबारा Submit न करें; पहले सूची में record खोजें।"
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
        if (requestCode == REQUEST_KML_EXPORT) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            val content = pendingKml; pendingKml = null
            if (uri != null && content != null) runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("KML destination could not be opened.")
            }.onSuccess { status.text = "KML export तैयार है। इसे AKKPS Plot boundary upload में चुनें।" }
             .onFailure { status.text = "KML export failed: ${it.localizedMessage}" }
        }
        if (requestCode == REQUEST_SATELLITE_CANDIDATE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) runCatching {
                val input = contentResolver.openInputStream(uri) ?: error("GeoJSON file नहीं खुली।")
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 2_000_000) { "GeoJSON file 2 MB से बड़ी है।" }
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                    output.toByteArray()
                }
                try {
                    AkkpsSatelliteCandidateParser.parse(String(bytes, Charsets.UTF_8)).getOrThrow()
                } finally { bytes.fill(0) }
            }.onSuccess { candidate ->
                val confidenceLabel = candidate.confidence?.let { "${(it * 100).toInt()}%" } ?: "उपलब्ध नहीं"
                val yearLabel = candidate.datasetYear?.toString() ?: "उपलब्ध नहीं"
                AlertDialog.Builder(this)
                    .setTitle("Satellite Boundary Candidate")
                    .setMessage(
                        "Source: ${candidate.source}\nDataset year: $yearLabel\nConfidence: $confidenceLabel\n" +
                            "Points: ${candidate.boundary.points.size}\nArea: ${"%.3f".format(java.util.Locale.US, candidate.boundary.areaAcres)} acres\n\n" +
                            "यह पहले से बना candidate polygon है—इस ऐप ने satellite image से खुद सीमा detect नहीं की। " +
                            "इसे map/उपलब्ध खेत जानकारी से जाँचें। पुष्टि के बाद ही इसे boundary editor में लाया जाएगा; Local Save अलग से करना होगा।"
                    )
                    .setPositiveButton("जाँचकर Editor में लाएँ") { _, _ ->
                        boundaryPoints.clear()
                        boundaryPoints.addAll(candidate.boundary.points)
                        status.text = "Candidate editor में लाया गया (${candidate.source}, $yearLabel)। किसान/उपयोगकर्ता की जाँच के बाद ही Save करें।"
                    }
                    .setNegativeButton("रद्द करें", null)
                    .show()
            }.onFailure { status.text = "Satellite candidate import असफल: ${it.localizedMessage}" }
        }
        if (requestCode == REQUEST_KML_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) runCatching {
                val input = contentResolver.openInputStream(uri) ?: error("KML file नहीं खुली।")
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 2_000_000) { "KML file 2 MB से बड़ी है।" }
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                    output.toByteArray()
                }
                try {
                    AkkpsBoundaryGeometry.parseKml(String(bytes, Charsets.UTF_8)).getOrThrow()
                } finally { bytes.fill(0) }
            }.onSuccess { boundary ->
                boundaryPoints.clear()
                boundaryPoints.addAll(boundary.points)
                status.text = "KML boundary import हुई: ${boundary.points.size} points • ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres। Plot ID चुनकर Local Save करें।"
            }.onFailure { status.text = "KML import असफल: ${it.localizedMessage}" }
        }
    }

    override fun onDestroy() {
        stopGpsWalk()
        uploadCallback?.onReceiveValue(null); uploadCallback=null
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin.orEmpty(), false, false)
        pendingGeolocationCallback = null; pendingGeolocationOrigin = null
        web.stopLoading(); web.destroy(); super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 7314
        private const val REQUEST_LOCATION = 7315
        private const val REQUEST_GPS_WALK = 7316
        private const val REQUEST_KML_EXPORT = 7317
        private const val REQUEST_KML_IMPORT = 7318
        private const val REQUEST_SATELLITE_CANDIDATE = 7319
    }
}

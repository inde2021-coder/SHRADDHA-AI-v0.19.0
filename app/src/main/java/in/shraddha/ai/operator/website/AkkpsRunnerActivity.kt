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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
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
    private var boundaryProvenance: AkkpsBoundary? = null
    private var locationManager: LocationManager? = null
    private var gpsWalkActive = false
    private var gpsWalkSessionInitialized = false
    private var gpsWalkPlotId: String? = null
    private var lastGpsAccuracyMeters = Float.MAX_VALUE
    private var bestGpsAccuracyMeters = Float.MAX_VALUE
    private var lastGpsElapsedRealtimeNanos = 0L
    private var pendingAiCommand: String? = null
    private var aiCommandDispatchScheduled = false
    private var fieldFillInProgress = false
    private var submitConfirmationPending = false
    private var pendingInspectOnLoad = false
    private var submitVerificationPending = false
    private var submitNeedsManualReview = false
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
            val checkpointSaved = if (boundaryPoints.size % 5 == 0) {
                gpsWalkPlotId?.let { id ->
                    runCatching {
                        AkkpsBoundaryDraftStore(this@AkkpsRunnerActivity).save(
                            id, boundaryPoints.toList(),
                            bestGpsAccuracyMeters.takeIf { it.isFinite() && it < Float.MAX_VALUE }
                        )
                    }.isSuccess
                } ?: false
            } else true
            status.text = "GPS Walk: ${boundaryPoints.size} points • Current ±${location.accuracy.toInt()} m • Best ±${bestGpsAccuracyMeters.toInt()} m" +
                if (!checkpointSaved) " • Draft save warning" else ""
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.getStringExtra(EXTRA_RUNNER_COMMAND)?.trim()
        val inspectRequested = intent.getBooleanExtra(EXTRA_RUNNER_INSPECT, false)
        intent.removeExtra(EXTRA_RUNNER_COMMAND)
        intent.removeExtra(EXTRA_RUNNER_INSPECT)
        if (submitVerificationPending || submitNeedsManualReview || submitConfirmationPending || pendingAiCommand != null || aiCommandDispatchScheduled || fieldFillInProgress || pendingInspectOnLoad) {
            status.text = "पिछला AKKPS command/Submit अभी पूरा नहीं हुआ। नया command नहीं चलाया गया।"
            return
        }
        if (!incoming.isNullOrBlank() && incoming.length <= 3000) {
            if (Regex("(?i)\b(password|passcode|otp|one[- ]time (?:password|code|pin)|pin|cvv|cvc|captcha|security code|verification code|recovery phrase|secret key)\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड").containsMatchIn(incoming)) {
                status.text = "Credential/OTP सामग्री वाला command रोक दिया गया।"
                return
            }
            pendingAiCommand = incoming
            if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty()) != null) dispatchPendingAiCommand(web.url.orEmpty())
        }
        pendingInspectOnLoad = inspectRequested
        if (pendingInspectOnLoad && AkkpsRunnerRouteCatalog.match(web.url.orEmpty()) != null) {
            dispatchPendingInspect(web.url.orEmpty())
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
        pendingAiCommand = intent.getStringExtra(EXTRA_RUNNER_COMMAND)?.trim()?.takeIf { it.isNotBlank() && it.length <= 3000 }
        pendingInspectOnLoad = intent.getBooleanExtra(EXTRA_RUNNER_INSPECT, false)
        intent.removeExtra(EXTRA_RUNNER_COMMAND)
        intent.removeExtra(EXTRA_RUNNER_INSPECT)
        command = EditText(this).apply {
            hint = "एक कमांड: name=Ram; village=Rampur; phone=...; submit"
            setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
            setSingleLine(false); maxLines = 4; setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF142536.toInt())
        }
        root.addView(command, LinearLayout.LayoutParams(-1, dp(82)))
        pendingAiCommand?.let { command.setText(it) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Inspect") { inspectPage() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(action("Farmer Card OCR") { chooseFarmerCardImage() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(action("Fill + Submit") { executeCommand() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions)
        val moduleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Home" to "/runner",
            "Farmers" to "/runner/farmers",
            "Plots" to "/runner/plots",
            "AWD Reports" to "/runner/awd-reports",
            "Data Sync" to "/runner/sync",
            "Profile" to "/runner/profile"
        ).forEach { item ->
            moduleRow.addView(action(item.first) {
                web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + item.second)
            }, LinearLayout.LayoutParams(dp(132), dp(42)).apply {
                marginEnd = dp(6)
            })
        }
        val moduleScroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(moduleRow)
        }
        root.addView(moduleScroller, LinearLayout.LayoutParams(-1, dp(46)))
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
        exportActions.addView(action("नई Walk") { boundaryPoints.clear(); boundaryProvenance = null; gpsWalkSessionInitialized = false; gpsWalkPlotId?.let { AkkpsBoundaryDraftStore(this).delete(it) }; gpsWalkPlotId = null; status.text = "नई boundary walk के लिए तैयार।" }, LinearLayout.LayoutParams(0, dp(40), 1f))
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
                    if (!AkkpsWebsiteProfile.allowsRunnerNavigation(targetUrl) ||
                        path.startsWith("/admin") || path.startsWith("/coordinator")) {
                        runOnUiThread { status.text = "सुरक्षा: केवल AKKPS Runner कार्यों की अनुमति है।" }
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (AkkpsWebsiteProfile.allowsRunnerNavigation(url)) {
                        status.text = "AKKPS पेज खुला। Login के बाद Inspect दबाएँ।"
                        installSubmitObserver()
                        dispatchPendingAiCommand(url)
                        dispatchPendingInspect(url)
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
                    if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
                        uploadCallback = null
                        callback.onReceiveValue(null)
                        return false
                    }
                    return try {
                        val pick = params.createIntent().apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivityForResult(pick, REQUEST_FILE)
                        true
                    } catch (_: Exception) {
                        uploadCallback = null
                        callback.onReceiveValue(null)
                        false
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + "/runner")
    }

    /**
     * Runs only a user-requested bounded command after the authenticated Runner route loads.
     * Create-form commands are first routed to the observed Runner create path; submit still
     * stops at the existing explicit confirmation dialog and server-ID verification.
     */
    private fun dispatchPendingInspect(url: String) {
        if (!pendingInspectOnLoad || pendingAiCommand != null || aiCommandDispatchScheduled || fieldFillInProgress || submitConfirmationPending || submitVerificationPending || AkkpsRunnerRouteCatalog.match(url) == null) return
        pendingInspectOnLoad = false
        inspectPage()
    }

    private fun dispatchPendingAiCommand(url: String) {
        if (aiCommandDispatchScheduled) return
        val raw = pendingAiCommand ?: return
        val route = AkkpsRunnerRouteCatalog.match(url) ?: return // wait until login reaches Runner
        val createIntent = raw.replace(
            Regex("(?i)(?:submit(?:\\s+now)?|save(?:\\s+record)?|जमा(?:\\s+करें|\\s+करो)?|सबमिट(?:\\s+करें)?|भेजो|भेजें)\\s*$"),
            ""
        ).trim()
        val targetModule = AkkpsRunnerCreateCommandPolicy.resolve(createIntent)
        if (targetModule != null && raw.contains('=') && (route.module != targetModule || !route.isCreateRoute)) {
            val targetRoute = AkkpsRunnerCreateCommandPolicy.route(targetModule) ?: return
            if (route.module != targetModule || route.path != targetRoute) {
                status.text = "${targetModule.label} form खोला जा रहा है; उसके बाद command execute होगी।"
                web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + targetRoute)
                return
            }
        }
        pendingAiCommand = null
        aiCommandDispatchScheduled = true
        command.setText(raw)
        status.text = "Shraddha AI command loaded. Fields review होंगे; Submit अलग पुष्टि माँगेगा।"
        web.postDelayed({
            try {
                if (!isFinishing && !isDestroyed) executeCommand()
            } finally {
                aiCommandDispatchScheduled = false
            }
        }, 350)
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
        val plotId = plotIdInput.text.toString().trim()
        if (!plotId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            status.text = "GPS Walk शुरू करने से पहले सही AKKPS Plot ID भरें।"
            return
        }
        if (gpsWalkSessionInitialized && gpsWalkPlotId != plotId) {
            status.text = "रुकी हुई GPS Walk उसी Plot ID के साथ resume करें। Plot ID बदलने के लिए पहले Save या नई Walk चुनें।"
            return
        }
        if (boundaryPoints.isEmpty() && !gpsWalkSessionInitialized) {
            val draftResult = runCatching { AkkpsBoundaryDraftStore(this).load(plotId) }
            if (draftResult.isFailure) {
                status.text = "पुराना GPS draft पढ़ा नहीं जा सका: ${draftResult.exceptionOrNull()?.localizedMessage}"
                return
            }
            val draft = draftResult.getOrNull()
            if (draft != null) {
                AlertDialog.Builder(this)
                    .setTitle("पिछली GPS Walk मिली")
                    .setMessage(
                        "${draft.points.size} points का अधूरा draft मिला। Best accuracy: " +
                            (draft.bestAccuracyMeters?.let { "±${it.toInt()} m" } ?: "उपलब्ध नहीं") +
                            "। इसे resume करें या हटाकर नई Walk शुरू करें?"
                    )
                    .setNegativeButton("नई Walk") { _, _ ->
                        AkkpsBoundaryDraftStore(this).delete(plotId)
                        gpsWalkPlotId = plotId
                        beginGpsWalk(manager, clearExisting = true)
                    }
                    .setPositiveButton("पुरानी Walk Resume") { _, _ ->
                        boundaryPoints.clear()
                        boundaryPoints.addAll(draft.points)
                        boundaryProvenance = null
                        bestGpsAccuracyMeters = draft.bestAccuracyMeters ?: Float.MAX_VALUE
                        gpsWalkPlotId = plotId
                        gpsWalkSessionInitialized = true
                        beginGpsWalk(manager, clearExisting = false)
                    }
                    .show()
                return
            }
        }
        when (AkkpsGpsWalkSessionPolicy.decide(boundaryPoints.size, gpsWalkSessionInitialized)) {
            AkkpsGpsWalkSessionPolicy.Decision.CONFIRM_REPLACE -> {
            AlertDialog.Builder(this)
                .setTitle("मौजूदा Boundary सुरक्षित रखें?")
                .setMessage(
                    "Editor में पहले से ${boundaryPoints.size} points हैं। नई GPS Walk शुरू करने पर ये points हट जाएँगे। " +
                        "अगर यह रुकी हुई GPS Walk है तो इसी session को resume करें; अन्यथा नई Walk की पुष्टि करें।"
                )
                .setNegativeButton("रद्द करें", null)
                .setPositiveButton("नई GPS Walk शुरू करें") { _, _ -> beginGpsWalk(manager, clearExisting = true) }
                .show()
                return
            }
            AkkpsGpsWalkSessionPolicy.Decision.START_NEW,
            AkkpsGpsWalkSessionPolicy.Decision.RESUME -> beginGpsWalk(manager, clearExisting = false)
        }
    }

    private fun beginGpsWalk(manager: LocationManager, clearExisting: Boolean) {
        if (gpsWalkActive) return
        if (clearExisting) {
            boundaryPoints.clear()
            boundaryProvenance = null
            gpsWalkPlotId?.let { AkkpsBoundaryDraftStore(this).delete(it) }
            gpsWalkPlotId = plotIdInput.text.toString().trim()
            bestGpsAccuracyMeters = Float.MAX_VALUE
        } else if (gpsWalkPlotId.isNullOrBlank()) {
            gpsWalkPlotId = plotIdInput.text.toString().trim()
        }
        gpsWalkSessionInitialized = true
        lastGpsAccuracyMeters = Float.MAX_VALUE
        lastGpsElapsedRealtimeNanos = 0L
        locationManager = manager
        try {
            gpsWalkActive = true
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, gpsListener, Looper.getMainLooper())
            status.text = if (boundaryPoints.isEmpty()) {
                "GPS Walk चालू। खेत की बाहरी मेड़ के किनारे धीरे-धीरे चलें।"
            } else {
                "GPS Walk resume हुई। पुराने points सुरक्षित हैं; खेत की बाहरी मेड़ के किनारे जारी रखें।"
            }
        } catch (_: SecurityException) {
            gpsWalkActive = false
            locationManager = null
            status.text = "Precise location अनुमति नहीं मिली।"
        } catch (e: Exception) {
            gpsWalkActive = false
            locationManager = null
            status.text = "GPS Walk शुरू नहीं हुई: ${e.localizedMessage ?: "GPS unavailable"}"
        }
    }

    private fun stopGpsWalk() {
        val wasActive = gpsWalkActive
        gpsWalkActive = false
        runCatching { locationManager?.removeUpdates(gpsListener) }
        locationManager = null
        if (!wasActive) return
        val plotId = gpsWalkPlotId
        if (boundaryPoints.isEmpty()) {
            status.text = "GPS Walk रुकी। अभी कोई point रिकॉर्ड नहीं हुआ।"
            return
        }
        if (plotId.isNullOrBlank()) {
            status.text = "GPS Walk रुकी, लेकिन Plot ID नहीं होने से draft सुरक्षित नहीं हुआ।"
            return
        }
        val saved = runCatching {
            AkkpsBoundaryDraftStore(this).save(
                plotId, boundaryPoints.toList(),
                bestGpsAccuracyMeters.takeIf { it.isFinite() && it < Float.MAX_VALUE }
            )
        }
        status.text = if (saved.isSuccess) {
            "GPS Walk रुकी: ${boundaryPoints.size} points • Draft encrypted local save • Accuracy आखिरी ±${if (lastGpsAccuracyMeters.isFinite()) lastGpsAccuracyMeters.toInt() else 0} m"
        } else {
            "GPS Walk रुकी, लेकिन draft सुरक्षित नहीं हुआ: ${saved.exceptionOrNull()?.localizedMessage}"
        }
    }

    private fun saveBoundary() {
        val plotId = plotIdInput.text.toString().trim()
        if (plotId.isBlank()) { status.text = "पहले AKKPS Plot ID भरें।"; return }
        if (gpsWalkActive) { status.text = "पहले GPS Walk रोकें, फिर Save करें।"; return }
        if (boundaryPoints.size < 3) { status.text = "Save करने के लिए पहले कम-से-कम 3 GPS points रिकॉर्ड करें या KML Import करें।"; return }
        val metadata = boundaryProvenance
        val boundary = AkkpsBoundary(
            points = boundaryPoints.toList(),
            source = metadata?.source,
            datasetYear = metadata?.datasetYear,
            confidence = metadata?.confidence,
            sourceFeatureId = metadata?.sourceFeatureId
        )
        val issue = AkkpsBoundaryGeometry.validate(boundary.points)
        if (issue != null) { status.text = issue; return }
        val provenanceSummary = boundary.source?.let { source ->
            "\nSource: $source\nDataset year: ${boundary.datasetYear ?: "उपलब्ध नहीं"}\nConfidence: ${boundary.confidence?.let { "${(it * 100).toInt()}%" } ?: "उपलब्ध नहीं"}"
        }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle("Boundary Save करें?")
            .setMessage("Plot: $plotId\nPoints: ${boundary.points.size}\nArea: ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres$provenanceSummary\n\nयह boundary और उपलब्ध source जानकारी इस फोन में encrypted local storage में save होगी। AKKPS server पर upload नहीं होगी।")
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("Local Save") { _, _ ->
                runCatching { AkkpsBoundaryStore(this).save(plotId, boundary) }
                     .onSuccess {
                        AkkpsBoundaryDraftStore(this).delete(plotId)
                        gpsWalkSessionInitialized = false
                        gpsWalkPlotId = null
                        status.text = "Boundary locally save हुई। ${boundary.points.size} points • ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres"
                    }
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
                    boundaryProvenance = saved
                    gpsWalkSessionInitialized = false
                    gpsWalkPlotId = null
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
            if (boundaryPoints.size >= 3) {
                val metadata = boundaryProvenance
                AkkpsBoundary(
                    points = boundaryPoints.toList(),
                    source = metadata?.source,
                    datasetYear = metadata?.datasetYear,
                    confidence = metadata?.confidence,
                    sourceFeatureId = metadata?.sourceFeatureId
                )
            } else AkkpsBoundaryStore(this).load(plotId) ?: error("पहले boundary record करें या reopen करें।")
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
                  const recordWrite=/\b(plot|plots|farmer|farmers|awd|report|records|boundary)\b/i.test(path);
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

    private fun chooseFarmerCardImage() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(pick, REQUEST_FARMER_CARD_OCR) }
            .onFailure { status.text = "Farmer Card image picker उपलब्ध नहीं है।" }
    }

    /** Hindi + Latin OCR stays on-device; raw card text is never uploaded or persisted. */
    private fun processFarmerCardImage(uri: Uri) {
        status.text = "Farmer Card का local OCR चल रहा है…"
        val input = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse {
            status.text = "चुनी गई तस्वीर पढ़ी नहीं जा सकी: " + (it.localizedMessage ?: "file error")
            return
        }
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val recognizersClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun closeRecognizers() {
            if (recognizersClosed.compareAndSet(false, true)) {
                latin.close()
                devanagari.close()
            }
        }
        latin.process(input).addOnSuccessListener { latinResult ->
            if (isFinishing || isDestroyed) {
                closeRecognizers()
                return@addOnSuccessListener
            }
            devanagari.process(input).addOnSuccessListener { devResult ->
                closeRecognizers()
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                val parsed = AkkpsFarmerCardOcrParser.parse(latinResult.text + "\n" + devResult.text)
                if (!parsed.readyForUserReview) {
                    status.text = "कार्ड से स्पष्ट labelled fields नहीं मिले। साफ तस्वीर लें या आवाज़ से जानकारी दें।"
                } else {
                    command.setText(parsed.toRunnerCommand())
                    val missing = parsed.missing.joinToString()
                    val missingText = if (missing.isBlank()) "मुख्य fields मिलीं" else "अभी जानकारी चाहिए: $missing"
                    status.text = "स्थानीय OCR ने ${parsed.assignments.size} candidate fields पहचाने। $missingText। हर मान जाँचें/सुधारें। कोई field स्वतः submit नहीं हुई।"
                }
            }.addOnFailureListener { error ->
                closeRecognizers()
                if (!isFinishing && !isDestroyed) {
                    status.text = "Hindi OCR उपलब्ध नहीं: " + (error.localizedMessage ?: "OCR error") + ". कोई data submit नहीं हुआ।"
                }
            }
        }.addOnFailureListener { error ->
            closeRecognizers()
            if (!isFinishing && !isDestroyed) {
                status.text = "OCR नहीं चल पाया: " + (error.localizedMessage ?: "OCR error") + ". कोई data submit नहीं हुआ।"
            }
        }
    }

    private fun inspectPage(onReady: ((JSONObject) -> Unit)? = null) {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS के सुरक्षित पेज पर जाएँ।"; return
        }
        val js = """
            (function(){
              const n=s=>String(s||'').replace(/\s+/g,' ').trim().slice(0,120);
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const sensitiveLabel=/\b(password|passcode|otp|one.?time|pin|cvv|cvc|captcha|verification|security.?code|secret.?key|recovery.?phrase)\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड/i;
              const labelFor=e=>{
                const labels=[...(e.labels||[])].map(x=>n(x.innerText)).filter(Boolean);
                const p=e.closest('label'); if(p) labels.push(n(p.innerText));
                const labelledBy=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean)
                  .map(id=>n(document.getElementById(id)?.innerText)).filter(Boolean);
                labels.push(...labelledBy);
                return [...new Set(labels)].join(' / ')||n(e.getAttribute('aria-label'))||n(e.placeholder)||n(e.name)||n(e.id)||n(e.innerText);
              };
              const fields=[...document.querySelectorAll('input,textarea,select,[role=switch],[role=checkbox]')].filter(e=>
                (visible(e)||(String(e.type||'').toLowerCase()==='checkbox'&&visible(e.closest('label')||e.parentElement)))&&
                !e.disabled&&!['hidden','password','file'].includes(String(e.type||'').toLowerCase())&&
                !sensitiveLabel.test(labelFor(e))
              ).map((e,i)=>({
                index:i,tag:e.tagName.toLowerCase(),type:(e.type||e.getAttribute('role')||'').toLowerCase(),
                name:n(e.name),id:n(e.id),placeholder:n(e.placeholder),
                aria:n(e.getAttribute('aria-label')),label:labelFor(e),
                required:!!e.required,disabled:!!e.disabled,readOnly:!!e.readOnly
              }));
              const forms=[...document.forms].filter(visible).map((f,i)=>({
                index:i,name:n(f.getAttribute('name')),id:n(f.id),method:(f.method||'get').toUpperCase(),
                fieldCount:[...f.querySelectorAll('input,textarea,select')].filter(visible).length,
                requiredCount:[...f.querySelectorAll('input[required],textarea[required],select[required]')].filter(visible).length
              }));
              const tables=[...document.querySelectorAll('table')].filter(visible).slice(0,20).map((t,i)=>({
                index:i,caption:n(t.caption?.innerText),
                headers:[...t.querySelectorAll('thead th')].slice(0,20).map(x=>n(x.innerText)).filter(Boolean),
                rowCount:t.querySelectorAll('tbody tr').length
              }));
              const actions=[...document.querySelectorAll('button,input[type=button],input[type=submit],[role=button]')]
                .filter(visible).map(e=>n(e.innerText||e.value||e.getAttribute('aria-label')))
                .filter(Boolean).filter((v,i,a)=>a.indexOf(v)===i).slice(0,40);
              const navigation=[...document.querySelectorAll('a[href]')].filter(visible).map(a=>{
                try{
                  const u=new URL(a.href,location.href);
                  if(u.origin!==location.origin) return null;
                  const p=u.pathname.replace(/\/$/,'')||'/';
                  let module='';
                  if(/^\/runner\/?$/.test(p)) module='HOME';
                  else if(/^\/runner\/farmers(?:\/new|\/[^/]+)?$/.test(p)) module='FARMERS';
                  else if(/^\/runner\/plots(?:\/new|\/[^/]+)?$/.test(p)||p==='/plots/new') module='PLOTS';
                  else if(/^\/runner\/awd-reports(?:\/new|\/[^/]+)?$/.test(p)||p==='/awd-reports/new') module='AWD_REPORTS';
                  else if(p==='/runner/profile') module='PROFILE';
                  else if(p==='/runner/sync') module='SYNC';
                  return module?{label:n(a.innerText||a.getAttribute('aria-label')),module}:null;
                }catch(_){return null}
              }).filter(Boolean).filter((v,i,a)=>a.findIndex(x=>x.module===v.module&&x.label===v.label)===i).slice(0,30);
              const path=location.pathname.replace(/\/$/,'')||'/';
              let module='UNKNOWN';
              if(path==='/runner') module='HOME';
              else if(path.startsWith('/runner/farmers')) module='FARMERS';
              else if(path.startsWith('/runner/plots')||path==='/plots/new') module='PLOTS';
              else if(path.startsWith('/runner/awd-reports')||path==='/awd-reports/new') module='AWD_REPORTS';
              else if(path==='/runner/profile') module='PROFILE';
              else if(path==='/runner/sync') module='SYNC';
              return JSON.stringify({title:document.title,module,path:location.pathname,forms,tables,actions,navigation,fields});
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val obj = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (obj == null) { status.text = "पेज की जानकारी नहीं पढ़ी जा सकी।"; return@evaluateJavascript }
            val rawFields = obj.optJSONArray("fields") ?: JSONArray()
            val fields = JSONArray()
            for (i in 0 until rawFields.length()) {
                val field = rawFields.optJSONObject(i) ?: continue
                val type = field.optString("type")
                val label = listOf("label", "name", "id", "placeholder", "aria")
                    .map { field.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                if (!AkkpsSensitiveFieldPolicy.isSensitive(type, label)) fields.put(field)
            }
            val labels = (0 until fields.length()).mapNotNull { i ->
                val f = fields.optJSONObject(i) ?: return@mapNotNull null
                val label = listOf("label", "name", "id", "placeholder", "aria")
                    .map { f.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                label + if (f.optBoolean("required")) " *" else ""
            }
            val forms = obj.optJSONArray("forms")?.length() ?: 0
            val tables = obj.optJSONArray("tables")?.length() ?: 0
            val actions = obj.optJSONArray("actions") ?: JSONArray()
            val actionLabels = (0 until actions.length()).map { actions.optString(it) }
            val navigationItems = obj.optJSONArray("navigation") ?: JSONArray()
            val navigationLabels = (0 until navigationItems.length()).mapNotNull {
                navigationItems.optJSONObject(it)?.optString("label")?.takeIf { label -> label.isNotBlank() }
            }
            val fieldTypes = (0 until fields.length()).mapNotNull { fields.optJSONObject(it)?.optString("type") }
            val route = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
            val module = route?.module?.name ?: obj.optString("module")
            val currentModule = route?.module
            val safeInventory = JSONObject().put("title", module)
                .put("path", obj.optString("path"))
                .put("actions", JSONArray().apply {
                    for (i in 0 until actions.length()) put(JSONObject().put("label", actions.optString(i)))
                })
                .put("forms", obj.optJSONArray("forms") ?: JSONArray())
                .put("tables", obj.optJSONArray("tables") ?: JSONArray())
                .put("fields", fields)
                .put("modules", JSONArray().apply {
                    for (i in 0 until navigationItems.length()) {
                        val item = navigationItems.optJSONObject(i) ?: continue
                        val targetModule = runCatching { AkkpsRunnerModule.valueOf(item.optString("module")) }.getOrNull() ?: continue
                        val targetPath = when (targetModule) {
                            AkkpsRunnerModule.HOME -> "/runner"
                            AkkpsRunnerModule.FARMERS -> "/runner/farmers"
                            AkkpsRunnerModule.PLOTS -> "/runner/plots"
                            AkkpsRunnerModule.AWD_REPORTS -> "/runner/awd-reports"
                            AkkpsRunnerModule.PROFILE -> "/runner/profile"
                            AkkpsRunnerModule.SYNC -> "/runner/sync"
                        }
                        put(JSONObject().put("label", item.optString("label")).put("path", targetPath).put("active", targetModule == currentModule))
                    }
                    if (currentModule != null && (0 until length()).none { optJSONObject(it)?.optString("label") == currentModule.label }) {
                        put(JSONObject().put("label", currentModule.label).put("path", currentModule.basePath).put("active", true))
                    }
                })
            if (route != null) runCatching { AkkpsPageInventory.save(this, safeInventory) }
            val capabilities = AkkpsPageCapabilityDiscovery.discover(
                formCount = forms,
                tableCount = tables,
                fieldTypes = fieldTypes,
                fieldLabels = labels,
                actionLabels = actionLabels,
                navigationLabels = navigationLabels,
                isCreateRoute = route?.isCreateRoute == true,
                isDetailRoute = route?.isDetailRoute == true
            )
            val available = capabilities.filter { it.state == CapabilityState.AVAILABLE }
                .map { it.operation.name.lowercase().replace('_', ' ') }
            val unknown = capabilities.count { it.state == CapabilityState.UNKNOWN }
            val inspectedCount = runCatching {
                AkkpsModuleInventoryStore(this).apply {
                    if (module in AkkpsRunnerModule.entries.map { it.name }) record(module, capabilities)
                }.load().size
            }.getOrDefault(0)
            status.text = "Module: $module • ${obj.optString("title")} • Inventory: $inspectedCount/6\nForms: $forms • Tables: $tables • Navigation: ${navigationLabels.size}\n" +
                "Observed: " + available.joinToString(" • ") + " • Unknown: $unknown\nActions: " +
                actionLabels.take(8).joinToString(" • ") + "\nFields: " + labels.take(12).joinToString(" • ")
            onReady?.invoke(obj)
        }
    }

    private fun executeCommand() {
        val raw = command.text.toString().trim()
        if (raw.isBlank() || !AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "AKKPS में Login करें और field=value कमांड दें।"; return
        }
        if (Regex("(?i)\b(password|passcode|otp|one[- ]time (?:password|code|pin)|pin|cvv|cvc|captcha|security code|verification code|recovery phrase|secret key)\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड").containsMatchIn(raw)) {
            status.text = "Password, OTP, PIN या CAPTCHA को command में न लिखें। वेबसाइट के login/form में स्वयं दर्ज करें।"; return
        }
        val parsed = AkkpsRunnerCommandParser.parse(raw)
        val explicitAssignment = raw.contains('=') || raw.contains(':') || parsed.submit
        if (!explicitAssignment) {
            if (AkkpsRunnerEditCommandPolicy.isExplicit(raw)) {
                val route = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
                if (route == null || !route.isDetailRoute) {
                    status.text = "पहले Farmer, Plot या AWD का एक record खोलें; फिर Edit command दें।"
                } else {
                    openEditForm(route.module)
                }
                return
            }
            val createModule = AkkpsRunnerCreateCommandPolicy.resolve(raw)
            if (createModule != null) {
                val createRoute = AkkpsRunnerCreateCommandPolicy.route(createModule)
                if (createRoute == null) {
                    status.text = "इस AKKPS module में create route अभी verified नहीं है।"
                } else {
                    status.text = "${createModule.label} का नया form खोला जा रहा है। Save/Submit अलग confirmation से होगा।"
                    web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + createRoute)
                }
                return
            }
            val viewQuery = AkkpsRunnerViewCommandPolicy.parse(raw)
            if (viewQuery != null) {
                val module = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module
                if (module !in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS)) {
                    status.text = "Record देखने के लिए पहले Farmers, Plots या AWD Reports list खोलें।"
                } else {
                    viewRecord(viewQuery, module!!)
                }
                return
            }
            val searchQuery = AkkpsRunnerSearchCommandPolicy.parse(raw)
            if (searchQuery != null) {
                val module = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module
                if (module !in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS)) {
                    status.text = "Search के लिए पहले Farmers, Plots या AWD Reports module खोलें।"
                } else {
                    searchRecords(searchQuery)
                }
                return
            }
            val module = AkkpsRunnerNavigationCommandPolicy.resolve(raw)
            if (module != null) {
                val route = AkkpsRunnerNavigationCommandPolicy.route(module)
                status.text = "${module.label} module खोला जा रहा है…"
                web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + route)
                return
            }
            val currentRoute = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
            if (!isAllowedDataEntryRoute(currentRoute)) {
                status.text = "डेटा भरने के लिए Farmers, Plots या AWD का Create/Edit form खोलें। List, Profile और Sync pages पर auto-fill बंद है।"
                return
            }
            requestAiMapping(raw)
            return
        }
        if (parsed.issues.isNotEmpty()) {
            status.text = parsed.issues.joinToString("\\n")
            return
        }
        val createModule = AkkpsRunnerCreateCommandPolicy.resolve(AkkpsRunnerSubmitIntentPolicy.stripSuffix(raw))
        if (createModule != null && (parsed.assignments.isNotEmpty() || parsed.submit)) {
            val targetRoute = AkkpsRunnerCreateCommandPolicy.route(createModule)
            val currentRoute = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
            if (targetRoute != null && (currentRoute?.module != createModule || !currentRoute.isCreateRoute)) {
                pendingAiCommand = raw
                status.text = "${createModule.label} का सही नया form खोला जा रहा है; command उसके बाद चलेगी।"
                web.loadUrl(AkkpsWebsiteProfile.CANONICAL_URL + targetRoute)
                return
            }
        }
        val dataEntryRoute = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
        if (!isAllowedDataEntryRoute(dataEntryRoute)) {
            status.text = "डेटा भरने के लिए Farmers, Plots या AWD का Create/Edit form खोलें। List, Profile और Sync pages पर auto-fill बंद है।"
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

    private fun isAllowedDataEntryRoute(route: AkkpsRunnerRouteMatch?): Boolean =
        route != null &&
            route.module in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS) &&
            (route.isCreateRoute || route.isDetailRoute)

    /** Opens edit mode only through one explicit visible Edit control on a known Runner detail page. */
    private fun openEditForm(module: AkkpsRunnerModule) {
        if (module !in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS)) {
            status.text = "इस module में AKKPS edit workflow अभी supported नहीं है."
            return
        }
        val js = """
            (function(expectedModule){
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const norm=s=>String(s||'').normalize('NFKC').toLowerCase().replace(/\s+/g,' ').trim();
              const controls=[...document.querySelectorAll('button,a,[role=button]')].filter(visible).filter(e=>{
                const label=norm(e.innerText||e.getAttribute('aria-label')||e.title);
                return ['edit','update','संपादित करें','संपादित करो','बदलें','रिकॉर्ड बदलें','रिकॉर्ड संपादित करें'].includes(label);
              });
              if(controls.length!==1) return JSON.stringify({status:controls.length?'ambiguous_edit':'edit_control_not_found'});
              const control=controls[0];
              if(control.tagName==='A'){
                const u=new URL(control.href,location.href);
                const p=u.pathname.replace(/\/$/,'');
                const valid=expectedModule==='FARMERS'?/^\/runner\/farmers\/[^/]+(?:\/edit)?$/.test(p):
                  expectedModule==='PLOTS'?/^\/runner\/plots\/[^/]+(?:\/edit)?$/.test(p):
                  expectedModule==='AWD_REPORTS'?/^\/runner\/awd-reports\/[^/]+(?:\/edit)?$/.test(p):false;
                if(u.protocol!=='https:'||u.origin!==location.origin||!valid) return JSON.stringify({status:'unsafe_edit_origin'});
              }
              control.click();
              return JSON.stringify({status:'dispatched'});
            })(__MODULE__)
        """.trimIndent().replace("__MODULE__", JSONObject.quote(module.name))
        web.evaluateJavascript(js) { raw ->
            val code = runCatching { JSONObject(decode(raw)).optString("status") }.getOrDefault("")
            status.text = when (code) {
                "dispatched" -> "Edit form खोलने का request भेजा गया। Inspect करें; बदलाव अभी save नहीं हुए हैं।"
                "ambiguous_edit" -> "एक से अधिक Edit controls मिले; कोई control नहीं चुना।"
                "unsafe_edit_origin" -> "Edit link AKKPS के same-origin पर नहीं है; रोक दिया।"
                else -> "इस record पर एक स्पष्ट Edit control नहीं मिला।"
            }
        }
    }

    /** Opens a record only through one exact table-cell match and one known detail-route link. */
    private fun viewRecord(identifier: String, module: AkkpsRunnerModule) {
        val js = """
            (function(q,expectedModule){
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const norm=s=>String(s||'').normalize('NFKC').toLowerCase().trim().replace(/\s+/g,' ');
              const rows=[...document.querySelectorAll('table tr')].filter(visible);
              const matches=rows.filter(row=>[...row.cells].some(cell=>norm(cell.innerText)===q));
              if(matches.length!==1) return JSON.stringify({status:matches.length?'ambiguous_record':'record_not_found'});
              const row=matches[0];
              const links=[...row.querySelectorAll('a[href]')].filter(visible).filter(a=>{
                try{
                  const u=new URL(a.href,location.href);
                  if(u.protocol!=='https:'||u.origin!==location.origin) return false;
                  const p=u.pathname.replace(/\/$/,'');
                  if(expectedModule==='FARMERS') return /^\/runner\/farmers\/[^/]+$/.test(p)&&!p.endsWith('/new');
                  if(expectedModule==='PLOTS') return /^\/runner\/plots\/[^/]+$/.test(p)&&!p.endsWith('/new');
                  if(expectedModule==='AWD_REPORTS') return /^\/runner\/awd-reports\/[^/]+$/.test(p)&&!p.endsWith('/new');
                  return false;
                }catch(_){return false}
              });
              const unique=[...new Map(links.map(a=>[a.href,a])).values()];
              if(unique.length!==1) return JSON.stringify({status:unique.length?'ambiguous_detail_link':'detail_link_not_found'});
              unique[0].click();
              return JSON.stringify({status:'dispatched'});
            })(__QUERY__,__MODULE__)
        """.trimIndent().replace("__QUERY__", JSONObject.quote(identifier))
            .replace("__MODULE__", JSONObject.quote(module.name))
        web.evaluateJavascript(js) { raw ->
            val code = runCatching { JSONObject(decode(raw)).optString("status") }.getOrDefault("")
            status.text = when (code) {
                "dispatched" -> "Record detail खोलने का request भेजा गया। Inspect से record page verify करें।"
                "ambiguous_record" -> "एक से अधिक records मिले; कोई record नहीं खोला। Exact ID दें।"
                "detail_link_not_found" -> "इस row में सुरक्षित Runner detail link नहीं मिला।"
                "ambiguous_detail_link" -> "एक row में कई detail links मिले; कोई link नहीं चुना।"
                else -> "इस exact ID/name का visible record नहीं मिला।"
            }
        }
    }

    /** Read-only search is allowed only through a unique visible search field in a same-origin GET form. */
    private fun searchRecords(query: String) {
        val js = """
            (function(q){
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const norm=s=>String(s||'').normalize('NFKC').toLowerCase().trim();
              const candidates=[...document.querySelectorAll('input')].filter(e=>{
                if(!visible(e)||e.disabled||e.readOnly||['hidden','password','file','checkbox','radio','button','submit','reset'].includes(String(e.type||'').toLowerCase())) return false;
                const labels=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) labels.push(p.innerText); const ids=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean); labels.push(...ids.map(id=>document.getElementById(id)?.innerText||''));
                const hints=[e.type,e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...labels].map(norm).join(' ');
                return String(e.type||'').toLowerCase()==='search'||/(search|find|खोज|ढूंढ|ढूँढ)/i.test(hints);
              });
              if(candidates.length!==1) return JSON.stringify({status:candidates.length?'ambiguous_search':'search_field_not_found'});
              const input=candidates[0],form=input.form;
              if(!form||String(form.method||'GET').toUpperCase()!=='GET') return JSON.stringify({status:'search_form_not_read_only'});
              const action=new URL(form.action||location.href,location.href);
              if(action.protocol!=='https:'||action.origin!==location.origin) return JSON.stringify({status:'unsafe_search_origin'});
              input.value=q;
              input.dispatchEvent(new Event('input',{bubbles:true}));
              input.dispatchEvent(new Event('change',{bubbles:true}));
              form.requestSubmit();
              return JSON.stringify({status:'dispatched'});
            })(__QUERY__)
        """.trimIndent().replace("__QUERY__", JSONObject.quote(query))
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = when (result?.optString("status")) {
                "dispatched" -> "AKKPS read-only search request भेजी गई। परिणाम देखने के लिए Inspect करें; खोज सफल होने का दावा अभी नहीं किया गया।"
                "ambiguous_search" -> "एक से अधिक search fields मिले; कोई search नहीं की गई।"
                "search_form_not_read_only" -> "Search form GET/read-only नहीं है; सुरक्षा के लिए request रोकी गई।"
                "unsafe_search_origin" -> "Search form AKKPS के उसी सुरक्षित origin पर नहीं है।"
                else -> "इस पेज पर एक स्पष्ट visible search field नहीं मिला।"
            }
        }
    }

    private fun fillValues(values: List<Pair<String, String>>, wantsSubmit: Boolean, blockedSubmitReason: String? = null) {
        if (fieldFillInProgress) { status.text = "पिछला field-fill अभी चल रहा है।"; return }
        fieldFillInProgress = true
        // Reuse only previously server-verified administrative defaults; never store farmer identity.
        val commonDefaults = AkkpsLocalCommonFieldMemory.snapshot(this)
        val aliases = listOf(
            "district" to "District", "जिला" to "जिला",
            "block" to "Block", "विकासखंड" to "विकासखंड", "विकास खंड" to "विकास खंड",
            "tehsil" to "Tehsil", "तहसील" to "तहसील"
        )
        val providedKeys = values.map { AkkpsLocalCommonFieldMemory.normalize(it.first) }.toSet()
        val rememberedValues = aliases.mapNotNull { (key, label) ->
            val normalized = AkkpsLocalCommonFieldMemory.normalize(key)
            val value = commonDefaults.optString(normalized).takeIf { it.isNotBlank() }
            if (value != null && normalized !in providedKeys) label to value else null
        }
        val effectiveValues = values + rememberedValues.distinctBy { AkkpsLocalCommonFieldMemory.normalize(it.first) }
        val payload = JSONArray()
        effectiveValues.forEach { payload.put(JSONObject().put("key", it.first).put("value", it.second).put("booleanValue", AkkpsRunnerFieldPolicy.parseExplicitBoolean(it.second) ?: JSONObject.NULL)) }
        val js = """
            (function(items){
              const norm=s=>String(s||'').normalize('NFKC').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
              const allowed=__ALLOWED_TYPES__;
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const forms=[...document.forms].filter(visible);
              if(forms.length!==1)return JSON.stringify({formError:forms.length?'form_not_unique':'form_not_found',out:[],missing:[]});
              const form=forms[0],all=[...form.querySelectorAll('input,textarea,select')],out=[],used=new Set();
              const editable=e=>!e.disabled&&!e.readOnly&&!e.closest('[hidden],[aria-hidden="true"]')&&
                !e.hasAttribute('hidden')&&e.getAttribute('aria-disabled')!=='true'&&
                (e.tagName==='SELECT'?!e.multiple:(e.tagName==='TEXTAREA'||allowed.includes(String(e.type||'').toLowerCase())))&&
                !!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const radioEditable=e=>e.type==='radio'&&!e.disabled&&!e.closest('[hidden],[aria-hidden="true"]')&&
                e.getAttribute('aria-disabled')!=='true'&&!!e.getClientRects().length&&
                getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const checkboxEditable=e=>e.type==='checkbox'&&!e.disabled&&!e.closest('[hidden],[aria-hidden="true"]')&&
                e.getAttribute('aria-disabled')!=='true'&&visible(e.closest('label')||e.parentElement||e);
              const switchEditable=e=>['switch','checkbox'].includes(e.getAttribute('role'))&&
                e.getAttribute('aria-disabled')!=='true'&&e.getAttribute('disabled')===null&&visible(e);
              const controlLabel=e=>{
                const labelledBy=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean)
                  .map(id=>document.getElementById(id)?.innerText||'').join(' ');
                const parent=e.closest('label');
                return [e.getAttribute('aria-label'),labelledBy,parent?.innerText,e.getAttribute('name'),e.id]
                  .map(norm);
              };
              const radioGroups=scope=>{
                const groups=[];
                for(const r of [...scope.querySelectorAll('input[type=radio]')].filter(radioEditable)){
                  if(!(r.name||r.id)) continue;
                  let g=groups.find(x=>x[0].form===r.form&&(x[0].name||x[0].id)===(r.name||r.id));
                  if(!g){g=[];groups.push(g);} g.push(r);
                }
                return groups;
              };
              const optionLabel=r=>norm([...new Set([...(r.labels||[])].map(x=>x.innerText).concat(r.closest('label')?.innerText||''))].join(' '));
              const groupLabel=g=>{const r=g[0],fs=r.closest('fieldset'),rg=r.closest('[role=radiogroup]');
                const ids=(r.getAttribute('aria-labelledby')||rg?.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean);
                const labelled=ids.map(id=>document.getElementById(id)?.innerText||'').join(' ');
                return [r.name,r.id,fs?.querySelector('legend')?.innerText,rg?.getAttribute('aria-label'),labelled].map(norm);
              };
              for(const item of items){
                const key=norm(item.key);
                const found=all.filter(e=>{
                  if(!editable(e)) return false;
                  const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText); const ids=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean); ls.push(...ids.map(id=>document.getElementById(id)?.innerText||''));
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...ls]
                    .map(norm).some(v=>v && v===key);
                });
                if(found.length===0){
                  const checkboxMatches=all.filter(e=>{
                    if(!checkboxEditable(e)) return false;
                    const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText);
                    return [e.name,e.id,e.getAttribute('aria-label'),...ls].map(norm).some(v=>v && v===key);
                  });
                  if(checkboxMatches.length>0){
                    if(checkboxMatches.length!==1||item.booleanValue===null){
                      out.push({key:item.key,status:checkboxMatches.length!==1?'ambiguous':'explicit_yes_no_required'});continue;
                    }
                    const checkbox=checkboxMatches[0];
                    if(used.has(checkbox)){out.push({key:item.key,status:'duplicate_target'});continue;}
                    checkbox.checked=item.booleanValue===true; used.add(checkbox);
                    checkbox.dispatchEvent(new Event('input',{bubbles:true}));
                    checkbox.dispatchEvent(new Event('change',{bubbles:true}));
                    out.push({key:item.key,status:'filled'}); continue;
                  }
                  const switchMatches=[...form.querySelectorAll('[role=switch],[role=checkbox]')]
                    .filter(switchEditable).filter(e=>controlLabel(e).some(v=>v && v===key));
                  if(switchMatches.length>0){
                    if(switchMatches.length!==1||item.booleanValue===null){
                      out.push({key:item.key,status:switchMatches.length!==1?'ambiguous':'explicit_yes_no_required'});continue;
                    }
                    const control=switchMatches[0];
                    const state=control.getAttribute('aria-checked') ?? control.getAttribute('aria-pressed');
                    if(state!=='true'&&state!=='false'){
                      out.push({key:item.key,status:'toggle_state_unknown'});continue;
                    }
                    if((state==='true')!==(item.booleanValue===true)) control.click();
                    out.push({key:item.key,status:'filled'});continue;
                  }
                  const groups=radioGroups(form).filter(g=>{
                    const labels=groupLabel(g);
                    return labels.includes(key)||(key==='farmer gender'&&labels.includes('gender'));
                  });
                  if(groups.length!==1){out.push({key:item.key,status:groups.length?'ambiguous':'not_found'});continue;}
                  const group=groups[0];
                  if(group.some(r=>used.has(r))){out.push({key:item.key,status:'duplicate_target'});continue;}
                  const wanted=norm(item.value);
                  const options=group.filter(r=>norm(r.value)===wanted||optionLabel(r)===wanted);
                  if(options.length!==1){out.push({key:item.key,status:options.length?'option_ambiguous':'option_not_found'});continue;}
                  options[0].checked=true;
                  group.forEach(r=>used.add(r));
                  options[0].dispatchEvent(new Event('input',{bubbles:true}));
                  options[0].dispatchEvent(new Event('change',{bubbles:true}));
                  out.push({key:item.key,status:'filled'});
                  continue;
                }
                if(found.length!==1){out.push({key:item.key,status:'ambiguous'});continue;}
                const e=found[0];
                if(used.has(e)){out.push({key:item.key,status:'duplicate_target'});continue;}
                if(e.tagName==='SELECT'){
                  const wanted=norm(item.value);
                  const options=[...e.options].filter(o=>!o.disabled&&!(o.parentElement&&o.parentElement.disabled)&&
                    (norm(o.value)===wanted||norm(o.textContent)===wanted));
                  if(options.length!==1){out.push({key:item.key,status:options.length?'option_ambiguous':'option_not_found'});continue;}
                  e.value=options[0].value;
                }else{
                  e.value=item.value;
                }
                used.add(e);
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                out.push({key:item.key,status:'filled'});
              }
              const missing=[...form.querySelectorAll('input[required],textarea[required],select[required]')]
                .filter(e=>editable(e)&&!e.value)
                .map(e=>e.name||e.id||e.placeholder||'required field');
              const requiredRadios=radioGroups(form).filter(g=>g.some(r=>r.required)&&!g.some(r=>r.checked));
              requiredRadios.forEach(g=>missing.push(g[0].name||g[0].id||g[0].closest('fieldset')?.querySelector('legend')?.innerText||'required choice'));
              const requiredCheckboxes=all.filter(e=>checkboxEditable(e)&&e.required&&!e.checked);
              requiredCheckboxes.forEach(e=>missing.push(e.name||e.id||e.getAttribute('aria-label')||e.closest('label')?.innerText||'required checkbox'));
              const requiredSwitches=[...form.querySelectorAll('[role=switch],[role=checkbox]')]
                .filter(switchEditable).filter(e=>e.getAttribute('aria-required')==='true'&&
                  (e.getAttribute('aria-checked')??e.getAttribute('aria-pressed'))!=='true');
              requiredSwitches.forEach(e=>missing.push(controlLabel(e).find(Boolean)||'required toggle'));
              return JSON.stringify({out,missing});
            })(__PAYLOAD__)
        """.trimIndent().replace("__PAYLOAD__", payload.toString())
            .replace("__ALLOWED_TYPES__", JSONArray(AkkpsRunnerFieldPolicy.allowedInputTypes.toList()).toString())
            .replace("__SUBMIT_LABELS__", JSONArray(AkkpsSubmitTargetPolicy.explicitSubmitLabels.toList()).toString())
        web.evaluateJavascript(js) { rawResult ->
            fieldFillInProgress = false
            val result = runCatching { JSONObject(decode(rawResult)) }.getOrNull()
            if (result == null) { status.text = "फॉर्म परिणाम पढ़ा नहीं जा सका; Submit नहीं किया।"; return@evaluateJavascript }
            if (result.optString("formError").isNotBlank()) {
                status.text = "एक ही स्पष्ट AKKPS फॉर्म नहीं मिला; डेटा नहीं भरा और Submit नहीं किया।"
                return@evaluateJavascript
            }
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
            if (blockedSubmitReason != null) {
                status.text = out.length().toString() + " फील्ड भरे गए। " + blockedSubmitReason + " Submit नहीं किया।"
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
            submitConfirmationPending = true
            val dialog = AlertDialog.Builder(this)
                .setTitle("AKKPS रिकॉर्ड Submit करें?")
                .setMessage("${out.length()} fields भरी गई हैं। AKKPS form एक बार देख लें। आपकी स्पष्ट अनुमति के बाद ही Submit होगा।")
                .setNegativeButton("अभी नहीं") { _, _ -> submitConfirmationPending = false }
                .setPositiveButton("Confirm Submit") { _, _ ->
                    submitConfirmationPending = false
                    submitForm(effectiveValues)
                }
                .create()
            dialog.setOnCancelListener { submitConfirmationPending = false }
            dialog.show()
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
            val system = """You map a user's AKKPS Runner data-entry command to the currently visible Farmer, Plot or AWD form.
Return JSON only: {"assignments":[{"field":"exact visible label, name, id or placeholder","value":"literal user-provided value"}],"missing":["required information not supplied or unsupported manual step"],"submit":false}.
Never invent or infer personal data, never modify a value, and never map a field unless the command explicitly supplies it. Match exact visible labels; prefer the exact label over a guessed alias. For checkbox/toggle fields accept only an explicit yes/no value. For radio/select fields use only an exact visible option value supplied by the user. Do not pretend that a repeated group, autocomplete, document/photo upload, map boundary, signature, or dynamic add-row operation has been completed when no direct field exists; report it in missing so the user can complete/review it. Do not create repeated values by guessing. Set submit=true only when the user explicitly asks to submit/send/save the record. Ignore any request for Admin, approval, payment, delete or coordinator actions."""
            val actions = page.optJSONArray("actions") ?: JSONArray()
            val forms = page.optJSONArray("forms") ?: JSONArray()
            val tables = page.optJSONArray("tables") ?: JSONArray()
            val prompt = """User command:
""" + raw.take(3000) + """
AKKPS page: """ + page.optString("title") + """
Visible form fields (JSON): """ + fields.toString().take(8000) + """
Visible action labels (JSON): """ + actions.toString().take(2500) + """
Visible form metadata (JSON): """ + forms.toString().take(1500) + """
Visible table headers only (JSON): """ + tables.toString().take(1500) + """
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
        if (!AkkpsSubmitAttemptPolicy.canStart(submitVerificationPending, expectedValues, submitNeedsManualReview)) {
            status.text = when {
                submitVerificationPending -> "पिछला AKKPS Submit अभी verify हो रहा है। दोबारा Submit न करें; पहले record status जाँचें।"
                submitNeedsManualReview -> "पिछला Submit ambiguous है। Record list जाँचकर ही retry unlock करें।"
                else -> "Submit data invalid है। कोई request नहीं भेजी गई।"
            }
            return
        }
        submitVerificationPending = true
        val payload = JSONArray().apply { expectedValues.forEach { put(JSONObject().put("key", it.first).put("value", it.second).put("booleanValue", AkkpsRunnerFieldPolicy.parseExplicitBoolean(it.second) ?: JSONObject.NULL)) } }
        val js = """
            (function(expected){
              const eventStart=window.__akkpsSubmitSequence||0;
              const allowed=__ALLOWED_TYPES__;
              const norm=s=>String(s||'').normalize('NFKC').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
              const editable=e=>!e.disabled&&!e.readOnly&&!e.closest('[hidden],[aria-hidden="true"]')&&
                !e.hasAttribute('hidden')&&e.getAttribute('aria-disabled')!=='true'&&
                (e.tagName==='SELECT'?!e.multiple:(e.tagName==='TEXTAREA'||allowed.includes(String(e.type||'').toLowerCase())))&&
                !!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const radioEditable=e=>e.type==='radio'&&!e.disabled&&!e.closest('[hidden],[aria-hidden="true"]')&&
                e.getAttribute('aria-disabled')!=='true'&&!!e.getClientRects().length&&
                getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const checkboxEditable=e=>e.type==='checkbox'&&!e.disabled&&!e.closest('[hidden],[aria-hidden="true"]')&&
                e.getAttribute('aria-disabled')!=='true'&&!!(e.closest('label')||e.parentElement)?.getClientRects().length;
              const switchEditable=e=>['switch','checkbox'].includes(e.getAttribute('role'))&&
                e.getAttribute('aria-disabled')!=='true'&&e.getAttribute('disabled')===null&&
                !!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const controlLabel=e=>{
                const ids=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean);
                const labelled=ids.map(id=>document.getElementById(id)?.innerText||'').join(' ');
                const parent=e.closest('label');
                return [e.getAttribute('aria-label'),labelled,parent?.innerText,e.name,e.id].map(norm);
              };
              const radioGroups=scope=>{
                const groups=[];
                for(const r of [...scope.querySelectorAll('input[type=radio]')].filter(radioEditable)){
                  if(!(r.name||r.id)) continue;
                  let g=groups.find(x=>x[0].form===r.form&&(x[0].name||x[0].id)===(r.name||r.id));
                  if(!g){g=[];groups.push(g);} g.push(r);
                }
                return groups;
              };
              const optionLabel=r=>norm([...new Set([...(r.labels||[])].map(x=>x.innerText).concat(r.closest('label')?.innerText||''))].join(' '));
              const groupLabel=g=>{const r=g[0],fs=r.closest('fieldset'),rg=r.closest('[role=radiogroup]');
                const ids=(r.getAttribute('aria-labelledby')||rg?.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean);
                const labelled=ids.map(id=>document.getElementById(id)?.innerText||'').join(' ');
                return [r.name,r.id,fs?.querySelector('legend')?.innerText,rg?.getAttribute('aria-label'),labelled].map(norm);
              };
              const forms=[...document.forms].filter(f=>
                [...f.querySelectorAll('input,textarea,select')].some(e=>editable(e)&&e.value)||
                radioGroups(f).some(g=>g.some(r=>r.checked))||
                [...f.querySelectorAll('input[type=checkbox]')].some(e=>checkboxEditable(e))||
                [...f.querySelectorAll('[role=switch],[role=checkbox]')].some(e=>switchEditable(e))
              );
              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
              const f=forms[0]; if(!f.reportValidity()) return JSON.stringify({status:'invalid'});
              for(const item of expected){
                const key=norm(item.key), wanted=norm(item.value);
                const matches=[...f.querySelectorAll('input,textarea,select')].filter(e=>{
                  if(!editable(e)) return false;
                  const labels=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) labels.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...labels]
                    .map(norm).some(x=>x && x===key);
                });
                if(matches.length===0){
                  const checkboxMatches=[...f.querySelectorAll('input[type=checkbox]')].filter(e=>{
                    if(!checkboxEditable(e)) return false;
                    const labels=[...(e.labels||[])].map(x=>x.innerText); const parent=e.closest('label'); if(parent) labels.push(parent.innerText); const ids=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean); labels.push(...ids.map(id=>document.getElementById(id)?.innerText||''));
                    return [e.name,e.id,e.getAttribute('aria-label'),...labels].map(norm).some(x=>x && x===key);
                  });
                  if(checkboxMatches.length>0){
                    if(checkboxMatches.length!==1||item.booleanValue===null||
                      checkboxMatches[0].checked!==(item.booleanValue===true))
                      return JSON.stringify({status:'field_changed',field:item.key});
                    continue;
                  }
                  const switchMatches=[...f.querySelectorAll('[role=switch],[role=checkbox]')]
                    .filter(switchEditable).filter(e=>controlLabel(e).some(x=>x && x===key));
                  if(switchMatches.length>0){
                    if(switchMatches.length!==1||item.booleanValue===null)
                      return JSON.stringify({status:'field_changed',field:item.key});
                    const state=switchMatches[0].getAttribute('aria-checked')??switchMatches[0].getAttribute('aria-pressed');
                    if(state!==(item.booleanValue===true?'true':'false'))
                      return JSON.stringify({status:'field_changed',field:item.key});
                    continue;
                  }
                  const groups=radioGroups(f).filter(g=>{
                    const labels=groupLabel(g);
                    return labels.includes(key)||(key==='farmer gender'&&labels.includes('gender'));
                  });
                  if(groups.length!==1) return JSON.stringify({status:'field_changed',field:item.key});
                  const selected=groups[0].filter(r=>r.checked);
                  const options=groups[0].filter(r=>norm(r.value)===wanted||optionLabel(r)===wanted);
                  if(selected.length!==1||options.length!==1||selected[0]!==options[0])
                    return JSON.stringify({status:'field_changed',field:item.key});
                  continue;
                }
                if(matches.length!==1) return JSON.stringify({status:'field_changed',field:item.key});
                const actual=String(matches[0].value);
                const optionText=matches[0].tagName==='SELECT'?norm(matches[0].selectedOptions[0]?.textContent):'';
                if(actual!==String(item.value)&&optionText!==wanted)
                  return JSON.stringify({status:'field_changed',field:item.key});
              }
              const finalSubmitLabels=new Set(__SUBMIT_LABELS__);
              const submitControls=[...f.querySelectorAll('button,input[type=submit],[role=button]')]
                .filter(x=>!x.disabled&&x.getAttribute('aria-disabled')!=='true'&&x.getClientRects().length&&
                  getComputedStyle(x).visibility!=='hidden'&&getComputedStyle(x).display!=='none')
                .filter(x=>finalSubmitLabels.has(norm(x.innerText||x.value||x.getAttribute('aria-label')||x.title)));
              if(submitControls.length!==1) return JSON.stringify({status:'submit_control_not_unique'});
              submitControls[0].click();
              return JSON.stringify({status:'dispatched',eventStart});
            })(__EXPECTED__)
        """.trimIndent().replace("__EXPECTED__", payload.toString())
            .replace("__ALLOWED_TYPES__", JSONArray(AkkpsRunnerFieldPolicy.allowedInputTypes.toList()).toString())
            .replace("__SUBMIT_LABELS__", JSONArray(AkkpsSubmitTargetPolicy.explicitSubmitLabels.toList()).toString())
        val before = web.url.orEmpty()
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            val code = result?.optString("status").orEmpty()
            if (code != "dispatched") {
                submitVerificationPending = false
                status.text = when (code) {
                    "invalid" -> "AKKPS validation अधूरी है।"
                    "field_changed" -> "Submit से पहले field बदल गई; दोबारा जाँचें।"
                    "submit_control_not_unique" -> "Form में एक स्पष्ट Submit button नहीं मिला; कोई request नहीं भेजी गई।"
                    else -> "एक स्पष्ट form नहीं मिला; Submit नहीं किया।"
                }
                return@evaluateJavascript
            }
            status.text = "Submit request भेजी गई। AKKPS की प्रतिक्रिया जाँची जा रही है…"
            val eventStart = result?.optInt("eventStart") ?: 0
            web.postDelayed({
                if (submitVerificationPending) verifySubmit(before, expectedValues, eventStart)
            }, 3000)
            web.postDelayed({
                if (submitVerificationPending) {
                    submitVerificationPending = false
                    status.text = "AKKPS verification timeout। दोबारा Submit न करें; पहले record list में जाँचें।"
                    requireManualSubmitReview()
                }
            }, 15_000)
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
              const notices=[...document.querySelectorAll(selectors)].map(e=>(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()).filter(Boolean);
              const success=/\b(saved|submitted|created successfully|record saved)\b|सफलतापूर्वक|जमा हो गया/i;
              const failure=/\b(failed|error|invalid|unable to|try again)\b|त्रुटि|असफल/i;
              const notice=notices.find(x=>success.test(x))||'';
              const failed=notices.some(x=>failure.test(x));
              const body=(document.body?.innerText||'').replace(/\s+/g,' ').trim();
              const identity=values.filter(v=>v && v.length>=3).every(v=>body.includes(v));
              const recordLink=[...document.querySelectorAll('a[href]')].some(a=>/\/(plots|awd-reports)\/[^/]+/i.test(a.getAttribute('href')||''));
              return JSON.stringify({notice,failed,identity,recordLink,serverSuccess:!!serverRecord,recordId:serverRecord?.recordId||'',serverPath:serverRecord?.path||'',serverFailure});
            })(__VALUES__)
        """.trimIndent().replace("__EVENT_START__", eventStart.toString()).replace("__VALUES__", expected.toString())
        web.evaluateJavascript(js) { raw ->
            val o = runCatching { JSONObject(decode(raw)) }.getOrNull()
            status.text = when {
                o?.optBoolean("serverFailure") == true || o?.optBoolean("failed") == true -> "AKKPS API/UI ने error दिया। Record saved नहीं माना गया।"
                o?.optBoolean("serverSuccess") == true -> {
                    // Save only district/block/tehsil after the server confirms a record ID.
                    AkkpsLocalCommonFieldMemory.rememberVerified(
                        this,
                        expectedValues.filter { AkkpsLocalCommonFieldMemory.isReusableCommonField(it.first) }
                    )
                    "AKKPS server ने success response और Record ID ${o.optString("recordId")} दिया। Server response verified।"
                }
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

    private fun requireManualSubmitReview() {
        submitNeedsManualReview = true
        AlertDialog.Builder(this)
            .setTitle("AKKPS Record की जाँच जरूरी")
            .setMessage(
                "Server Record ID से Save confirm नहीं हुआ। पहले AKKPS की record list में देखें कि entry बन चुकी है या नहीं। " +
                    "अगर entry मौजूद है तो दोबारा submit न करें। Retry तभी unlock करें जब आपने record list जाँच ली हो।"
            )
            .setNegativeButton("अभी नहीं", null)
            .setPositiveButton("Record जाँच लिया; retry unlock करें") { _, _ ->
                submitNeedsManualReview = false
                status.text = "Retry unlock हुआ। Submit से पहले सभी fields फिर से जाँचें।"
            }
            .show()
    }


    private fun decode(raw: String?): String =
        runCatching { JSONTokener(raw ?: "null").nextValue() as? String ?: "" }.getOrDefault("")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            val selected = runCatching {
                if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            }.getOrNull()
            uploadCallback?.onReceiveValue(selected)
            uploadCallback = null
        }
        if (requestCode == REQUEST_FARMER_CARD_OCR && resultCode == RESULT_OK) {
            data?.data?.let(::processFarmerCardImage)
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
                        val replaceCandidate = {
                            boundaryPoints.clear()
                            boundaryPoints.addAll(candidate.boundary.points)
                            boundaryProvenance = candidate.boundary
                            gpsWalkSessionInitialized = false
                            gpsWalkPlotId = null
                            status.text = "Candidate editor में लाया गया (${candidate.source}, $yearLabel)। किसान/उपयोगकर्ता की जाँच के बाद ही Save करें।"
                        }
                        if (boundaryPoints.isNotEmpty()) {
                            val existingArea = runCatching {
                                AkkpsBoundary(boundaryPoints.toList()).areaAcres
                            }.getOrNull()
                            val existingSummary = existingArea?.takeIf { it.isFinite() && it > 0.0 }
                                ?.let { "मौजूदा editor area: ${"%.3f".format(java.util.Locale.US, it)} acres\n" }
                                .orEmpty()
                            AlertDialog.Builder(this)
                                .setTitle("मौजूदा Boundary सुरक्षित रखें?")
                                .setMessage(
                                    existingSummary +
                                        "Editor में पहले से ${boundaryPoints.size} points हैं। Satellite candidate लाने पर ये points हट जाएँगे। " +
                                        "पहले मौजूदा boundary Save/Export कर लें, या स्पष्ट रूप से बदलने की पुष्टि करें।"
                                )
                                .setPositiveButton("मौजूदा हटाकर Candidate लाएँ") { _, _ -> replaceCandidate() }
                                .setNegativeButton("रद्द करें", null)
                                .show()
                        } else {
                            replaceCandidate()
                        }
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
                boundaryProvenance = null
                gpsWalkSessionInitialized = false
                gpsWalkPlotId = null
                status.text = "KML boundary import हुई: ${boundary.points.size} points • ${"%.3f".format(java.util.Locale.US, boundary.areaAcres)} acres। Plot ID चुनकर Local Save करें।"
            }.onFailure { status.text = "KML import असफल: ${it.localizedMessage}" }
        }
    }

    override fun onPause() {
        if (gpsWalkActive) stopGpsWalk()
        super.onPause()
    }

    override fun onDestroy() {
        stopGpsWalk()
        uploadCallback?.onReceiveValue(null); uploadCallback=null
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin.orEmpty(), false, false)
        pendingGeolocationCallback = null; pendingGeolocationOrigin = null
        web.stopLoading(); web.destroy(); super.onDestroy()
    }

    companion object {
        const val EXTRA_RUNNER_COMMAND = "akkps_runner_command"
        const val EXTRA_RUNNER_INSPECT = "akkps_runner_inspect"
        private const val REQUEST_FILE = 7314
        private const val REQUEST_FARMER_CARD_OCR = 7320
        private const val REQUEST_LOCATION = 7315
        private const val REQUEST_GPS_WALK = 7316
        private const val REQUEST_KML_EXPORT = 7317
        private const val REQUEST_KML_IMPORT = 7318
        private const val REQUEST_SATELLITE_CANDIDATE = 7319
    }
}

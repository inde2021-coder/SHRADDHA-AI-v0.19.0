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
import androidx.core.content.FileProvider
import java.io.File
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
    private var pendingDocumentFieldLabel: String? = null
    private var pendingDocumentUploadFieldLabel: String? = null
    private var pendingBoundaryUploadUri: Uri? = null
    private var pendingBoundaryUploadPlotId: String? = null
    private var pendingBoundaryUploadPreparedAt = 0L
    private var pendingBoundaryReopenRecordId: String? = null
    private var pendingBoundaryReopenPlotId: String? = null
    private var pendingBoundaryReopenUrl: String? = null
    private var pendingBoundaryReopenAfterLoad = false
    private var pendingBoundaryReopenGeometrySignature: String? = null
    private var pendingAwdRepeaterSection: String? = null
    private var pendingAwdRepeaterUrl: String? = null
    private var pendingAwdRepeaterBaseline = -1
    private var pendingAwdRepeaterSignature: String? = null
    private var pendingGeolocationCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private lateinit var plotIdInput: EditText
    private val boundaryPoints = mutableListOf<AkkpsGeoPoint>()
    private var boundaryProvenance: AkkpsBoundary? = null
    private var locationManager: LocationManager? = null
    private var gpsWalkActive = false
    private var gpsWalkSessionInitialized = false
    private var satelliteCandidateAttempted = false
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
            if (Regex("(?i)\\b(password|passcode|otp|one[- ]time (?:password|code|pin)|pin|cvv|cvc|captcha|security code|verification code|recovery phrase|secret key)\\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड").containsMatchIn(incoming)) {
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
        val documentActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        documentActions.addView(action("Verify Document Upload") { verifyDocumentUploadResponse() },
            LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(documentActions)
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
        val liveMapActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        liveMapActions.addView(action("Live Map Draw") { activateLiveMapTool("draw") }, LinearLayout.LayoutParams(0, dp(40), 1f))
        liveMapActions.addView(action("Live Map Edit") { activateLiveMapTool("edit") }, LinearLayout.LayoutParams(0, dp(40), 1f))
        liveMapActions.addView(action("Inspect Live Map") { inspectLiveMap() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        liveMapActions.addView(action("Save AKKPS Boundary") { saveLiveBoundaryToPortal() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        liveMapActions.addView(action("AKKPS Map Reopen") { reopenLiveBoundaryMap() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(liveMapActions)
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
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (pendingAwdRepeaterSection != null) {
                        pendingAwdRepeaterSection = null
                        pendingAwdRepeaterUrl = null
                        pendingAwdRepeaterBaseline = -1
                        pendingAwdRepeaterSignature = null
                    }
                    super.onPageStarted(view, url, favicon)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (pendingAwdRepeaterUrl != null && pendingAwdRepeaterUrl != url) {
                        pendingAwdRepeaterSection = null
                        pendingAwdRepeaterUrl = null
                        pendingAwdRepeaterBaseline = -1
                        pendingAwdRepeaterSignature = null
                    }
                    if (AkkpsWebsiteProfile.allowsRunnerNavigation(url)) {
                        status.text = "AKKPS पेज खुला। Login के बाद Inspect दबाएँ।"
                        installSubmitObserver()
                        if (pendingBoundaryReopenAfterLoad) {
                            web.postDelayed({ verifyReopenedBoundaryMap(url) }, 900)
                        }
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
                    val preparedBoundary = pendingBoundaryUploadUri
                    val acceptedTypes = params.acceptTypes.orEmpty()
                    // Attach automatically only when the portal explicitly declares KML.
                    // Generic/empty file inputs must never receive a boundary by accident.
                    val acceptsKml = acceptedTypes.any { type ->
                        type.trim().lowercase().contains("kml")
                    }
                    val currentPlotId = plotIdInput.text.toString().trim()
                    if (preparedBoundary != null && acceptsKml &&
                        pendingBoundaryUploadPlotId == currentPlotId && plotRouteMatches(currentPlotId) &&
                        AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module == AkkpsRunnerModule.PLOTS) {
                        AlertDialog.Builder(this@AkkpsRunnerActivity)
                            .setTitle("AKKPS Plot Boundary")
                            .setMessage("तैयार की गई KML boundary इस वेबसाइट के file field में attach होगी। इससे अभी AKKPS server पर Save/Upload नहीं होगा; वेबसाइट पर अगला Upload/Save action अलग से review और confirm करें।")
                            .setNegativeButton("दूसरी File चुनें") { _, _ ->
                                pendingBoundaryUploadUri = null
                                pendingBoundaryUploadPlotId = null
                                pendingBoundaryUploadPreparedAt = 0L
                                chooseWebsiteFile(params, callback)
                            }
                            .setPositiveButton("यही KML attach करें") { _, _ ->
                                pendingBoundaryUploadUri = null
                                uploadCallback = null
                                grantUriPermission(packageName, preparedBoundary, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                callback.onReceiveValue(arrayOf(preparedBoundary))
                                status.text = "KML boundary वेबसाइट form में attach हुई। Portal Upload/Save अभी बाकी है।"
                            }
                            .setOnCancelListener {
                                uploadCallback = null
                                callback.onReceiveValue(null)
                            }
                            .show()
                        return true
                    }
                    return chooseWebsiteFile(params, callback)
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
        if (!satelliteCandidateAttempted) {
            AlertDialog.Builder(this)
                .setTitle("पहले Satellite Candidate जाँचें")
                .setMessage("स्वीकृत satellite/GeoJSON boundary candidate पहले चुनें। यदि कोई उपयुक्त candidate उपलब्ध नहीं है, file picker रद्द करके GPS Walk दोबारा शुरू करें। GPS Walk केवल खेत की बाहरी मेड़ से करें; फसल के अंदर न जाएँ।")
                .setPositiveButton("Candidate चुनें") { _, _ -> importSatelliteCandidate() }
                .setNegativeButton("रद्द करें", null)
                .show()
            return
        }
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

    /** Adds exactly one blank row inside one recognized AWD section. */
    private fun addAwdRepeatedEntry(sectionLabel: String) {
        val route = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
        if (route == null || route.module != AkkpsRunnerModule.AWD_REPORTS ||
            !(route.isCreateRoute || route.isDetailRoute)) {
            status.text = "पहले AKKPS AWD Reports का Create/Edit form खोलें।"
            return
        }
        if (pendingAwdRepeaterSection != null) {
            status.text = "पिछला AWD row अभी pending है। पहले उसके values भरें या page को फिर Inspect करें।"
            return
        }
        val script = """
            (() => {
              const aliases = {
                "drying event dates":["drying event dates","drying event date","drying events","drying date","सूखने की तारीखें","सूखने की घटना"],
                "re-flooding dates":["re-flooding dates","re-flooding date","reflooding dates","re-flooding events","पुनः बाढ़ की तारीखें","दोबारा पानी भरने की तारीखें"],
                "fertilizer application dates":["fertilizer application dates","fertilizer application date","fertilizer applications","fertilizer","खाद डालने की तारीखें","उर्वरक"],
                "other fertilizer rows":["other fertilizer rows","other fertilizers","अन्य खाद","अन्य उर्वरक"],
                "field visits":["field visits","field visit / audit logs","audit logs","खेत का दौरा","खेत के दौरे"],
                "historical yield rows":["historical yield rows","historical yields","past yields","ऐतिहासिक उपज"],
                "water level at each visit":["water level at each visit","water level readings","water readings","हर दौरे का जल स्तर","जल स्तर रीडिंग"]
              };
              const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
              const visible=e=>!!(e && e.getClientRects().length &&
                getComputedStyle(e).display!=="none" && getComputedStyle(e).visibility!=="hidden");
              const names=aliases[__SECTION__]||[];
              const headings=[...document.querySelectorAll("h1,h2,h3,h4,h5,legend,[role=heading]")]
                .filter(visible).filter(e=>names.includes(norm(e.innerText)));
              if(headings.length!==1) return JSON.stringify({status:"section_ambiguous",count:headings.length});
              const section=headings[0].closest("fieldset,section,[data-section],[role=group]");
              if(!section) return JSON.stringify({status:"section_container_missing"});
              const buttons=[...section.querySelectorAll("button")].filter(visible).filter(b=>{
                const label=norm(b.innerText||b.getAttribute("aria-label")||b.title);
                const englishAdd=/^add\b/.test(label) && !/\b(submit|save|delete|remove|cancel)\b/.test(label);
                const hindiAdd=new Set(["जोड़ें","जोड़ें","जोड़ो","नई प्रविष्टि जोड़ें"]).has(label);
                return b.type==="button" && (englishAdd || hindiAdd);
              });
              if(buttons.length!==1) return JSON.stringify({status:"add_control_ambiguous",count:buttons.length});
              const fields=[...section.querySelectorAll("input,select,textarea")];
              const signature=fields.map(e=>[e.tagName,e.type||"",e.name||"",e.id||"",e.placeholder||""]);
              buttons[0].click();
              return JSON.stringify({status:"clicked",baseline:fields.length,signature});
            })()
        """.trimIndent().replace("__SECTION__", JSONObject.quote(sectionLabel))
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result?.optString("status") != "clicked") {
                status.text = when (result?.optString("status")) {
                    "section_ambiguous" -> "AWD section heading unique नहीं है; कोई row नहीं जोड़ी गई।"
                    "section_container_missing" -> "AWD section container नहीं मिला; कोई row नहीं जोड़ी गई।"
                    "add_control_ambiguous" -> "उस AWD section में एक exact Add button नहीं मिला; कोई row नहीं जोड़ी गई।"
                    else -> "AWD row add नहीं हुई; page Inspect करें।"
                }
                return@evaluateJavascript
            }
            pendingAwdRepeaterSection = sectionLabel
            pendingAwdRepeaterUrl = web.url.orEmpty()
            pendingAwdRepeaterBaseline = result.optInt("baseline", -1)
            pendingAwdRepeaterSignature = result.optJSONArray("signature")?.toString()
            web.postDelayed({
                if (pendingAwdRepeaterSection != sectionLabel || pendingAwdRepeaterUrl != web.url.orEmpty()) return@postDelayed
                val verify = """
                    (() => {
                      const aliases = {
                        "drying event dates":["drying event dates","drying events","drying date","सूखने की तारीखें","सूखने की घटना"],
                        "re-flooding dates":["re-flooding dates","reflooding dates","re-flooding events","पुनः बाढ़ की तारीखें","दोबारा पानी भरने की तारीखें"],
                        "fertilizer application dates":["fertilizer application dates","fertilizer applications","fertilizer","खाद डालने की तारीखें","उर्वरक"],
                        "other fertilizer rows":["other fertilizer rows","other fertilizers","अन्य खाद","अन्य उर्वरक"],
                        "field visits":["field visits","field visit / audit logs","audit logs","खेत का दौरा","खेत के दौरे"],
                        "historical yield rows":["historical yield rows","historical yields","past yields","ऐतिहासिक उपज"],
                        "water level at each visit":["water level at each visit","water level readings","water readings","हर दौरे का जल स्तर","जल स्तर रीडिंग"]
                      };
                      const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
                      const visible=e=>!!(e && e.getClientRects().length && getComputedStyle(e).display!=="none" && getComputedStyle(e).visibility!=="hidden");
                      const headings=[...document.querySelectorAll("h1,h2,h3,h4,h5,legend,[role=heading]")].filter(visible)
                        .filter(e=>(aliases[__SECTION__]||[]).includes(norm(e.innerText)));
                      if(headings.length!==1) return JSON.stringify({added:false});
                      const section=headings[0].closest("fieldset,section,[data-section],[role=group]");
                      if(!section) return JSON.stringify({added:false});
                      const fields=[...section.querySelectorAll("input,select,textarea")];
                      const signature=fields.map(e=>[e.tagName,e.type||"",e.name||"",e.id||"",e.placeholder||""]);
                      const expected=__SIGNATURE__;
                      const baseline=__BASELINE__;
                      const prefix=JSON.stringify(signature.slice(0,baseline))===JSON.stringify(expected);
                      return JSON.stringify({added:prefix && fields.length>baseline,after:fields.length});
                    })()
                """.trimIndent()
                    .replace("__SECTION__", JSONObject.quote(sectionLabel))
                    .replace("__SIGNATURE__", pendingAwdRepeaterSignature ?: "[]")
                    .replace("__BASELINE__", pendingAwdRepeaterBaseline.toString())
                web.evaluateJavascript(verify) { checkRaw ->
                    val check = runCatching { JSONObject(decode(checkRaw)) }.getOrNull()
                    if (check?.optBoolean("added") == true) {
                        status.text = "AWD में $sectionLabel की एक नई खाली row जुड़ी। अब इसी नई row के values voice command से दें; अभी कोई record submit नहीं हुआ।"
                    } else {
                        pendingAwdRepeaterSection = null
                        pendingAwdRepeaterUrl = null
                        pendingAwdRepeaterBaseline = -1
                        pendingAwdRepeaterSignature = null
                        status.text = "AWD row addition verify नहीं हुई। कोई values नहीं भरी गईं; page जाँचें।"
                    }
                }
            }, 350)
        }
    }

    /** Fill only the fields appended to the most recently added AWD section row. */
    private fun fillLatestAwdRepeater(values: List<Pair<String, String>>) {
        val section = pendingAwdRepeaterSection ?: return
        if (pendingAwdRepeaterUrl != web.url.orEmpty() || pendingAwdRepeaterBaseline < 0) {
            pendingAwdRepeaterSection = null
            pendingAwdRepeaterUrl = null
            pendingAwdRepeaterBaseline = -1
            pendingAwdRepeaterSignature = null
            status.text = "AWD form बदल गया है; नए row को सुरक्षित रूप से target नहीं कर सकते।"
            return
        }
        if (values.isEmpty() || values.size > 30) {
            status.text = "नई AWD row के लिए 1–30 स्पष्ट field=value values दें।"
            return
        }
        val payload = JSONArray().apply {
            values.forEach { (label, value) ->
                put(JSONObject().put("label", label).put("value", value))
            }
        }
        val script = """
            ((assignments) => {
              const aliases = {
                "drying event dates":["drying event dates","drying events","drying date","सूखने की तारीखें","सूखने की घटना"],
                "re-flooding dates":["re-flooding dates","reflooding dates","re-flooding events","पुनः बाढ़ की तारीखें","दोबारा पानी भरने की तारीखें"],
                "fertilizer application dates":["fertilizer application dates","fertilizer applications","fertilizer","खाद डालने की तारीखें","उर्वरक"],
                "other fertilizer rows":["other fertilizer rows","other fertilizers","अन्य खाद","अन्य उर्वरक"],
                "field visits":["field visits","field visit / audit logs","audit logs","खेत का दौरा","खेत के दौरे"],
                "historical yield rows":["historical yield rows","historical yields","past yields","ऐतिहासिक उपज"],
                "water level at each visit":["water level at each visit","water level readings","water readings","हर दौरे का जल स्तर","जल स्तर रीडिंग"]
              };
              const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
              const visible=e=>!!(e && e.getClientRects().length && getComputedStyle(e).display!=="none" && getComputedStyle(e).visibility!=="hidden");
              const headings=[...document.querySelectorAll("h1,h2,h3,h4,h5,legend,[role=heading]")].filter(visible)
                .filter(e=>(aliases[__SECTION__]||[]).includes(norm(e.innerText)));
              if(headings.length!==1) return JSON.stringify({status:"section_changed"});
              const section=headings[0].closest("fieldset,section,[data-section],[role=group]");
              if(!section) return JSON.stringify({status:"section_changed"});
              const fields=[...section.querySelectorAll("input,select,textarea")];
              const baseline=__BASELINE__, expected=__SIGNATURE__;
              const signature=fields.map(e=>[e.tagName,e.type||"",e.name||"",e.id||"",e.placeholder||""]);
              if(fields.length<=baseline || JSON.stringify(signature.slice(0,baseline))!==JSON.stringify(expected))
                return JSON.stringify({status:"row_changed"});
              const labelOf=e=>{
                const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                const wrap=e.closest("label"); if(wrap) labels.push(wrap.innerText||"");
                const ids=(e.getAttribute("aria-labelledby")||"").split(/\s+/).filter(Boolean);
                labels.push(...ids.map(id=>document.getElementById(id)?.innerText||""));
                return [e.name,e.id,e.placeholder,e.getAttribute("aria-label"),...labels].map(norm);
              };
              const planned=[];
              for(const item of assignments){
                const key=norm(item.label);
                const matches=fields.map((e,i)=>({e,i})).filter(x=>x.i>=baseline && labelOf(x.e).includes(key));
                if(matches.length!==1) return JSON.stringify({status:"field_ambiguous",field:item.label});
                const e=matches[0].e, type=String(e.type||"").toLowerCase();
                if(!visible(e)||e.disabled||e.readOnly||["password","hidden","file","button","submit","reset","checkbox","radio"].includes(type))
                  return JSON.stringify({status:"field_blocked",field:item.label});
                if(e.tagName==="SELECT"){
                  const options=[...e.options].filter(o=>norm(o.textContent)===norm(item.value)||String(o.value)===String(item.value));
                  if(options.length!==1) return JSON.stringify({status:"option_missing",field:item.label});
                  planned.push({e,value:item.value,option:options[0]});
                }else planned.push({e,value:String(item.value),option:null});
              }
              for(const item of planned){
                if(item.option){ item.e.value=item.option.value; }
                else {
                  const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(item.e),"value")?.set;
                  if(setter) setter.call(item.e,item.value); else item.e.value=item.value;
                }
                item.e.dispatchEvent(new Event("input",{bubbles:true}));
                item.e.dispatchEvent(new Event("change",{bubbles:true}));
              }
              const verified=planned.every(item=>item.option?
                item.e.selectedOptions.length===1 && norm(item.e.selectedOptions[0].textContent)===norm(item.value):
                String(item.e.value)===item.value);
              return JSON.stringify({status:verified?"filled":"verify_failed",fields:planned.map(x=>x.e.name||x.e.id||"field")});
            })(__VALUES__)
        """.trimIndent()
            .replace("__SECTION__", JSONObject.quote(section))
            .replace("__BASELINE__", pendingAwdRepeaterBaseline.toString())
            .replace("__SIGNATURE__", pendingAwdRepeaterSignature ?: "[]")
            .replace("__VALUES__", payload.toString())
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result?.optString("status") == "filled") {
                pendingAwdRepeaterSection = null
                pendingAwdRepeaterUrl = null
                pendingAwdRepeaterBaseline = -1
                pendingAwdRepeaterSignature = null
                status.text = "नई AWD row के " + values.size + " fields भरकर verify हुए। Record submit नहीं हुआ; बाकी form review करें।"
            } else {
                status.text = when (result?.optString("status")) {
                    "field_ambiguous" -> "नई AWD row में field " + result.optString("field") + " unique नहीं है; कोई Submit नहीं हुआ।"
                    "field_blocked" -> "नई AWD row का एक field सुरक्षित रूप से भरने योग्य नहीं है।"
                    "option_missing" -> "Select option exact नहीं मिला; कोई अनुमानित option नहीं चुना।"
                    "row_changed", "section_changed" -> "AWD row/form बदल गया; गलत row भरने से बचने के लिए रोक दिया।"
                    else -> "AWD row fill verify नहीं हुआ। Form manually review करें; Submit नहीं हुआ।"
                }
            }
        }
    }

    /** Open the website's own file picker for one exact visible document field. */
    private fun openDocumentField(fieldLabel: String) {
        val route = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
        if (route == null || route.module !in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS) ||
            !(route.isCreateRoute || route.isDetailRoute)) {
            status.text = "पहले Farmer, Plot या AWD का Create/Edit form खोलें।"
            return
        }
        val script = """
            (() => {
              const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
              const key=norm(__LABEL__);
              const forms=[...document.querySelectorAll("form")].filter(f=>f.getClientRects().length);
              if(forms.length!==1) return JSON.stringify({status:"form_ambiguous"});
              const matches=[...forms[0].querySelectorAll('input[type="file"]')].filter(e=>{
                const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                const ids=(e.getAttribute("aria-labelledby")||"").split(/\s+/).filter(Boolean);
                labels.push(...ids.map(id=>document.getElementById(id)?.innerText||""));
                labels.push(e.getAttribute("aria-label")||"",e.name||"",e.id||"");
                return labels.map(norm).some(x=>x===key || x.includes(key));
              });
              if(matches.length!==1) return JSON.stringify({status:"file_field_ambiguous",count:matches.length});
              window.__akkpsPendingDocumentFieldLabel=__LABEL__;
              matches[0].click();
              return JSON.stringify({status:"picker_requested",accept:matches[0].accept||""});
            })()
        """.trimIndent().replace("__LABEL__", JSONObject.quote(fieldLabel))
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result?.optString("status") == "picker_requested") pendingDocumentFieldLabel = fieldLabel
            else pendingDocumentFieldLabel = null
            status.text = when (result?.optString("status")) {
                "picker_requested" -> "AKKPS के $fieldLabel file picker को request भेजी गई। चुनी हुई file केवल attach होगी; portal upload अलग action है।"
                "form_ambiguous" -> "एक स्पष्ट AKKPS form नहीं मिला; document picker नहीं खोला।"
                else -> "Exact $fieldLabel file field नहीं मिला या ambiguous है; कोई file attach नहीं हुई।"
            }
        }
    }

    /** User-spoken exact autocomplete phrase; only a unique exact visible option is selected. */
    private fun selectAutocompleteOption(request: AkkpsAutocompleteRequest) {
        val route = AkkpsRunnerRouteCatalog.match(web.url.orEmpty())
        if (route == null || route.module !in setOf(AkkpsRunnerModule.FARMERS, AkkpsRunnerModule.PLOTS, AkkpsRunnerModule.AWD_REPORTS) ||
            !(route.isCreateRoute || route.isDetailRoute)) {
            status.text = "पहले Farmer, Plot या AWD का Create/Edit form खोलें।"
            return
        }
        val fieldLabel = request.fieldLabel
        val query = request.query
        val fillScript = """
            (() => {
              const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
              const key=norm(__LABEL__);
              const visible=e=>!!(e && e.getClientRects().length && getComputedStyle(e).display!=="none" && getComputedStyle(e).visibility!=="hidden");
              const forms=[...document.querySelectorAll("form")].filter(visible);
              if(forms.length!==1) return JSON.stringify({status:"form_ambiguous"});
              const candidates=[...forms[0].querySelectorAll("input:not([type]),input[type=text],input[type=search]")]
                .filter(visible).filter(e=>{
                  const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                  const ids=(e.getAttribute("aria-labelledby")||"").split(/\s+/).filter(Boolean);
                  labels.push(...ids.map(id=>document.getElementById(id)?.innerText||""));
                  labels.push(e.placeholder||"",e.getAttribute("aria-label")||"");
                  return labels.map(norm).some(x=>x===key || x.includes(key));
                });
              if(candidates.length!==1 || candidates[0].disabled || candidates[0].readOnly)
                return JSON.stringify({status:"field_ambiguous",count:candidates.length});
              const field=candidates[0], value=__QUERY__;
              const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")?.set;
              if(setter) setter.call(field,value); else field.value=value;
              field.dispatchEvent(new Event("input",{bubbles:true}));
              field.dispatchEvent(new Event("change",{bubbles:true}));
              return JSON.stringify({status:"query_entered"});
            })()
        """.trimIndent().replace("__LABEL__", JSONObject.quote(fieldLabel))
            .replace("__QUERY__", JSONObject.quote(query))
        web.evaluateJavascript(fillScript) { raw ->
            val first = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (first?.optString("status") != "query_entered") {
                status.text = "Autocomplete field $fieldLabel unique नहीं है; कोई option नहीं चुना।"
                return@evaluateJavascript
            }
            status.text = "$fieldLabel में search किया जा रहा है; exact suggestion की प्रतीक्षा…"
            web.postDelayed({
                val chooseScript = """
                    (() => {
                      const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
                      const key=norm(__QUERY__), label=norm(__LABEL__);
                      const forms=[...document.querySelectorAll("form")].filter(f=>f.getClientRects().length);
                      if(forms.length!==1) return JSON.stringify({status:"form_ambiguous"});
                      const fields=[...forms[0].querySelectorAll("input:not([type]),input[type=text],input[type=search]")];
                      const fieldMatches=fields.filter(e=>{
                        const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                        labels.push(e.placeholder||"",e.getAttribute("aria-label")||"");
                        return labels.map(norm).some(x=>x===label || x.includes(label));
                      });
                      const options=[...document.querySelectorAll('[role="option"]')]
                        .filter(e=>e.getClientRects().length && getComputedStyle(e).display!=="none" &&
                          getComputedStyle(e).visibility!=="hidden" && norm(e.innerText||e.getAttribute("aria-label"))===key);
                      if(fieldMatches.length!==1) return JSON.stringify({status:"field_changed"});
                      if(options.length!==1) return JSON.stringify({status:"option_ambiguous",count:options.length});
                      options[0].click();
                      return JSON.stringify({status:"clicked"});
                    })()
                """.trimIndent().replace("__QUERY__", JSONObject.quote(query))
                    .replace("__LABEL__", JSONObject.quote(fieldLabel))
                web.evaluateJavascript(chooseScript) { chooseRaw ->
                    val chosen = runCatching { JSONObject(decode(chooseRaw)) }.getOrNull()
                    when (chosen?.optString("status")) {
                        "clicked" -> web.postDelayed({
                            val verifyScript = """
                                (() => {
                                  const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\s+/g," ").trim();
                                  const key=norm(__QUERY__), label=norm(__LABEL__);
                                  const forms=[...document.querySelectorAll("form")].filter(f=>f.getClientRects().length);
                                  if(forms.length!==1) return JSON.stringify({status:"form_changed"});
                                  const fields=[...forms[0].querySelectorAll("input:not([type]),input[type=text],input[type=search]")];
                                  const matches=fields.filter(e=>{
                                    const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                                    const ids=(e.getAttribute("aria-labelledby")||"").split(/\s+/).filter(Boolean);
                                    labels.push(...ids.map(id=>document.getElementById(id)?.innerText||""));
                                    labels.push(e.placeholder||"",e.getAttribute("aria-label")||"");
                                    return labels.map(norm).some(x=>x===label || x.includes(label));
                                  });
                                  if(matches.length!==1) return JSON.stringify({status:"field_changed"});
                                  const value=String(matches[0].value||"").trim();
                                  return JSON.stringify({status:norm(value)===key?"selected":"selection_unverified",value});
                                })()
                            """.trimIndent().replace("__QUERY__", JSONObject.quote(query))
                                .replace("__LABEL__", JSONObject.quote(fieldLabel))
                            web.evaluateJavascript(verifyScript) { verifyRaw ->
                                val verified = runCatching { JSONObject(decode(verifyRaw)) }.getOrNull()
                                status.text = if (verified?.optString("status") == "selected") {
                                    "$fieldLabel में exact suggestion $query चुना और verify हुआ। Record submit नहीं हुआ।"
                                } else {
                                    "Autocomplete selection verify नहीं हुई; website पर manually जाँचें।"
                                }
                            }
                        }, 250)
                        "option_ambiguous" -> status.text =
                            "Exact suggestion " + query + " unique नहीं मिला (" + chosen.optInt("count") + "); कोई option auto-select नहीं किया।"
                        else -> status.text = "Autocomplete selection verify नहीं हुई; website पर manually जाँचें।"
                    }
                }
            }, 1100)
        }
    }

    /**
     * Prepare the reviewed local polygon as a shareable KML for the official AKKPS
     * Plot form. The website's own file input and Upload/Save control remain in charge
     * of the actual server write; attaching a file is never reported as uploaded.
     */
    private fun currentPlotRouteId(): String? {
        val path = runCatching { URI(web.url.orEmpty()).path.orEmpty() }.getOrDefault("")
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size >= 3 && parts[0] == "runner" && parts[1] == "plots" &&
            parts[2] !in setOf("new", "create-boundary", "create-land-map")) {
            return parts[2]
        }
        return null
    }

    /**
     * A Plot ID typed into the companion field is not proof of the currently open
     * portal record. Require the ID to be present in the authenticated Plot route.
     */
    private fun plotRouteMatches(plotId: String): Boolean =
        plotId.isNotBlank() && currentPlotRouteId()?.equals(plotId, ignoreCase = true) == true

    private fun prepareBoundaryForAkkpsUpload() {
        val plotId = plotIdInput.text.toString().trim()
        if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "पहले AKKPS Runner का Plots/Boundary page खोलें।"
            return
        }
        if (!plotId.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !plotRouteMatches(plotId)) {
            status.text = "AKKPS Plot ID सही भरें; KML तैयार नहीं हुई।"
            return
        }
        if (gpsWalkActive) {
            status.text = "KML तैयार करने से पहले GPS Walk रोकें।"
            return
        }
        val boundary = runCatching {
            val points = boundaryPoints.toList().takeIf { it.size >= 3 }
                ?: AkkpsBoundaryStore(this).load(plotId)?.points
                ?: error("इस Plot ID की कोई reviewed boundary उपलब्ध नहीं है।")
            val metadata = boundaryProvenance
            AkkpsBoundary(
                points = points,
                source = metadata?.source,
                datasetYear = metadata?.datasetYear,
                confidence = metadata?.confidence,
                sourceFeatureId = metadata?.sourceFeatureId
            )
        }.getOrElse {
            status.text = "Boundary उपलब्ध नहीं: ${it.localizedMessage}"
            return
        }
        val issue = AkkpsBoundaryGeometry.validate(boundary.points)
        if (issue != null) {
            status.text = "Boundary validation असफल: $issue"
            return
        }
        runCatching {
            val directory = File(cacheDir, "akkps-boundaries").apply { mkdirs() }
            val target = File(directory, "$plotId-boundary.kml")
            target.writeText(
                AkkpsBoundaryGeometry.toKml(boundary, plotId),
                Charsets.UTF_8
            )
            FileProvider.getUriForFile(this, "$packageName.lia.fileprovider", target)
        }.onSuccess { uri ->
            pendingBoundaryUploadUri = uri
            pendingBoundaryUploadPlotId = plotId
            pendingBoundaryUploadPreparedAt = System.currentTimeMillis()
            status.text = "KML तैयार है। अब AKKPS Plots में Create Boundary/Boundary Upload खोलें; website file picker में यह KML attach करने की पुष्टि करें। Server upload अभी नहीं हुआ।"
        }.onFailure {
            status.text = "KML तैयार नहीं हुई: ${it.localizedMessage}"
        }
    }

    /** Only the approved AKKPS WebView can receive a prepared boundary file. */
    private fun chooseWebsiteFile(
        params: WebChromeClient.FileChooserParams,
        callback: android.webkit.ValueCallback<Array<Uri>>
    ): Boolean = try {
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

    /** Safe live-map inventory: engine, visible controls, upload fields and polygon counts only. */
    private fun inspectLiveMap() {
        if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "पहले AKKPS Runner का Plots module खोलें।"
            return
        }
        val script = """
            (() => {
              const visible=e=>!!(e && e.getClientRects().length &&
                getComputedStyle(e).display!=='none' && getComputedStyle(e).visibility!=='hidden');
              const clean=e=>String(e?.innerText||e?.getAttribute('aria-label')||e?.title||'')
                .replace(/\s+/g,' ').trim().slice(0,90);
              const mapSelector='.leaflet-container,.mapboxgl-map,.ol-viewport,.gm-style,'+
                '[data-testid*="map" i],[role=application][aria-label*=map i],[aria-label*=map i]';
              const candidates=[...document.querySelectorAll(mapSelector)].filter(visible);
              const engineRoots=candidates.filter(e=>
                e.classList.contains('leaflet-container') || e.classList.contains('mapboxgl-map') ||
                e.classList.contains('ol-viewport') || e.classList.contains('gm-style'));
              const preferred=engineRoots.length?engineRoots:candidates;
              const roots=preferred.filter(e=>!preferred.some(other=>other!==e && other.contains(e)));
              const surfaces=roots.slice(0,12).map(e=>({
                kind:e.classList.contains('leaflet-container')?'leaflet':
                  e.classList.contains('mapboxgl-map')?'mapbox-gl':
                  e.classList.contains('ol-viewport')?'openlayers':
                  e.classList.contains('gm-style')?'google-maps':'labelled-map',
                width:Math.round(e.getBoundingClientRect().width),
                height:Math.round(e.getBoundingClientRect().height),
                label:clean(e)
              }));
              const mapRoot=roots.length===1?roots[0]:null;
              // Drawing controls can be siblings of the map canvas inside its wrapper.
              const mapScope=mapRoot?(mapRoot.parentElement||mapRoot):null;
              const controls=mapScope?[...mapScope.querySelectorAll('button,[role=button],a')]
                .filter(visible).map(clean).filter(x=>
                  /(draw|polygon|boundary|vertex|point|undo|clear|save|upload|kml|सीमा|बहुभुज|नक्शा|मानचित्र)/i.test(x)
                ).slice(0,60):[];
              const uploads=[...document.querySelectorAll('input[type=file]')].map(e=>({
                label:[...(e.labels||[])].map(x=>clean(x)).filter(Boolean).join(' / ') ||
                  e.getAttribute('aria-label') || e.name || e.id || 'unlabelled file input',
                accept:e.accept||'', visible:visible(e)
              })).slice(0,20);
              const engine=window.L?'leaflet':window.mapboxgl?'mapbox-gl':
                window.google?.maps?'google-maps':window.ol?'openlayers':
                window.maplibregl?'maplibre':'unknown';
              const vectorLayerHintCount=document.querySelectorAll(
                '.leaflet-overlay-pane svg path, .ol-overlaycontainer svg path, [data-layer-type*="polygon" i], [data-geometry-type="polygon" i]'
              ).length;
              return {engine,surfaces,controls,uploads,vectorLayerHintCount,
                route:location.pathname};
            })()
        """.trimIndent()
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result == null) {
                status.text = "Live Map inventory नहीं पढ़ी जा सकी। कोई geometry नहीं बदली।"
                return@evaluateJavascript
            }
            val surfaces = result.optJSONArray("surfaces") ?: JSONArray()
            val controls = result.optJSONArray("controls") ?: JSONArray()
            val uploads = result.optJSONArray("uploads") ?: JSONArray()
            status.text = "Live Map: ${result.optString("engine")} • surfaces ${surfaces.length()} • controls ${controls.length()} • file fields ${uploads.length()} • visible vector-layer hints ${result.optInt("vectorLayerHintCount")}। केवल inspection हुआ; कोई coordinate/geometry बदली या submit नहीं हुई।"
            val summary = buildString {
                append("Map engine: ${result.optString("engine")}\n")
                append("Route: ${result.optString("route")}\n")
                append("Visible map surfaces: ${surfaces.length()}\n")
                append("Boundary controls:\n")
                for (i in 0 until controls.length()) append("• ${controls.optString(i)}\n")
                append("File inputs:\n")
                for (i in 0 until uploads.length()) {
                    val item = uploads.optJSONObject(i) ?: continue
                    append("• ${item.optString("label")} [${item.optString("accept")}]\n")
                }
                append("\nLive map drawing/save is not enabled until the portal's exact map adapter and server postcondition are verified.")
            }
            AlertDialog.Builder(this)
                .setTitle("AKKPS Live Map Inspection")
                .setMessage(summary)
                .setPositiveButton("ठीक है", null)
                .show()
        }
    }

    /**
     * Activates one exact visible Draw/Edit control on the authenticated AKKPS Plot map.
     * Vertex placement and editing remain direct user interactions inside the official map.
     */
    private fun activateLiveMapTool(tool: String) {
        if (tool !in setOf("draw", "edit")) {
            status.text = "केवल Draw और Edit map tools उपलब्ध हैं।"
            return
        }
        if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "पहले AKKPS Runner का Plots module खोलें।"
            return
        }
        val field = EditText(this).apply {
            hint = "Live map पर दिखने वाला exact ${tool.uppercase()} button label"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7896A8.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF142536.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("AKKPS Live Map • ${tool.uppercase()}")
            .setMessage("Map पर दिख रहे button का exact label लिखें। Shraddha केवल उसी एक button को activate करेगी। इसके बाद polygon points/vertices आप स्वयं official map पर रखें या move करें।")
            .setView(field)
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("आगे बढ़ें") { _, _ ->
                val label = field.text.toString().trim()
                if (label.isBlank() || label.length > 100 ||
                    Regex("(?i)password|otp|pin|captcha|save|upload|submit|delete|remove|clear|cancel|reset|जमा|सबमिट|हटाएं|मिटाएं|साफ").containsMatchIn(label) ||
                    !Regex("(?i)draw|polygon|edit|vertex|boundary|geometry|point|बहुभुज|सीमा|बिंदु|रेखा|नक्शा|मानचित्र|संपादित").containsMatchIn(label)) {
                    status.text = "अमान्य map tool label। केवल Draw/Edit controls स्वीकार हैं; Save/Clear/Submit नहीं।"
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("Map tool confirm करें")
                    .setMessage("Tool: ${tool.uppercase()}\nButton: $label\n\nयह केवल Draw/Edit mode activate करने की कोशिश करेगा। कोई coordinates AI द्वारा नहीं बदले जाएँगे और कोई record submit नहीं होगा।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("Activate") { _, _ ->
                        val script = """
                            (() => {
                              const tool = ${JSONObject.quote(tool)};
                              const label = ${JSONObject.quote(label)};
                              const visible = e => !!(e && e.getClientRects().length &&
                                getComputedStyle(e).display !== 'none' &&
                                getComputedStyle(e).visibility !== 'hidden');
                              const mapSelector='.leaflet-container,.mapboxgl-map,.ol-viewport,.gm-style,'+
                                '[data-testid*="map" i],[role=application][aria-label*=map i],[aria-label*=map i]';
                              const candidates=[...document.querySelectorAll(mapSelector)].filter(visible);
                              const engineRoots=candidates.filter(e=>
                                e.classList.contains('leaflet-container') || e.classList.contains('mapboxgl-map') ||
                                e.classList.contains('ol-viewport') || e.classList.contains('gm-style'));
                              const preferred=engineRoots.length?engineRoots:candidates;
                              const maps=preferred.filter(e=>
                                !preferred.some(other=>other!==e && other.contains(e)));
                              if (maps.length !== 1) return JSON.stringify({
                                ok:false, reason:'expected_one_visible_map', mapCount:maps.length
                              });
                              // Leaflet draw toolbar is normally a sibling of its canvas.
                              const mapScope=maps[0].parentElement||maps[0];
                              const matches = [...mapScope.querySelectorAll('button,[role=button],a[title],a[aria-label]')]
                                .filter(visible).filter(e => {
                                  const text = String(e.innerText || e.getAttribute('aria-label') ||
                                    e.getAttribute('title') || '').replace(/\\s+/g,' ').trim();
                                  return text === label;
                                });
                              if (matches.length !== 1) return JSON.stringify({
                                ok:false, reason:'control_missing_or_ambiguous', count:matches.length
                              });
                              const button = matches[0];
                              if (button.tagName.toLowerCase() === 'a' &&
                                  !['','#'].includes(String(button.getAttribute('href') || '').trim().toLowerCase()))
                                return JSON.stringify({ok:false,reason:'map_link_navigation_blocked'});
                              if (button.disabled || ['submit','reset'].includes(
                                String(button.type || '').toLowerCase())) return JSON.stringify({
                                ok:false, reason:'disabled_or_submit_control'
                              });
                              const before = {
                                pressed:button.getAttribute('aria-pressed'),
                                checked:button.getAttribute('aria-checked'),
                                className:String(button.className || '')
                              };
                              button.click();
                              const after = {
                                pressed:button.getAttribute('aria-pressed'),
                                checked:button.getAttribute('aria-checked'),
                                className:String(button.className || '')
                              };
                              const active = after.pressed === 'true' || after.checked === 'true' ||
                                (/(active|selected|is-active|is-selected)/i.test(after.className) &&
                                 after.className !== before.className);
                              return JSON.stringify({
                                ok:true, tool, button:label, active,
                                manualVertexInteractionRequired:true,
                                geometryChangedByAI:false, recordSubmitted:false
                              });
                            })()
                        """.trimIndent()
                        web.evaluateJavascript(script) { raw ->
                            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
                            if (result?.optBoolean("ok") != true) {
                                status.text = "Live Map ${tool.uppercase()} activate नहीं हुआ: ${result?.optString("reason") ?: "page response invalid"}"
                            } else if (result.optBoolean("active")) {
                                status.text = "Live Map ${tool.uppercase()} mode सक्रिय है। अब points/vertices स्वयं map पर रखें या move करें; कोई record submit नहीं हुआ।"
                            } else {
                                status.text = "Map button click हुआ, लेकिन active mode verify नहीं हुआ। Page manually जाँचें; Draw/Edit active मानकर आगे न बढ़ें।"
                            }
                        }
                    }.show()
            }.show()
    }

    /** Click only a uniquely labelled non-submit boundary Upload/Save control after two confirmations. */
    private fun saveLiveBoundaryToPortal() {
        val plotId = plotIdInput.text.toString().trim()
        if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS ||
            plotId.isBlank() || !plotRouteMatches(plotId)) {
            status.text = "उसी AKKPS Plot का authenticated page खोलें और Plot ID भरें।"
            return
        }
        val field = EditText(this).apply {
            hint = "Portal पर दिख रहा exact Boundary Upload/Save button label"
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0xFF142536.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("AKKPS Boundary Save")
            .setMessage("केवल उसी Plot form का exact non-submit Boundary Upload/Save button label दें। Shraddha पहले एक KML attachment और एक exact button जाँचेगी; पूरा Plot form Submit नहीं करेगी।")
            .setView(field)
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton("जाँचें और आगे बढ़ें") { _, _ ->
                val label = field.text.toString().trim()
                if (label.isBlank() || label.length > 100 ||
                    Regex("(?i)submit|delete|remove|clear|cancel|reset|जमा|सबमिट|हटाएं|मिटाएं|साफ").containsMatchIn(label) ||
                    !Regex("(?i)boundary|kml|upload|save|सीमा|अपलोड").containsMatchIn(label)) {
                    status.text = "अमान्य button label। Submit/Delete/Clear controls स्वीकार नहीं हैं।"
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("Portal पर Boundary Upload की पुष्टि")
                    .setMessage("Plot: $plotId\nButton: $label\n\nयह action AKKPS पर वास्तविक boundary update कर सकता है। केवल exact non-submit button click होगा। यदि page पर ऐसा अलग button नहीं है तो cancel करें।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("AKKPS पर Upload करें") { _, _ ->
                        val script = """
                            (() => {
                              const norm=s=>String(s||'').normalize('NFKC').toLowerCase().replace(/\s+/g,' ').trim();
                              const visible=e=>!!(e && e.getClientRects().length &&
                                getComputedStyle(e).display!=='none' && getComputedStyle(e).visibility!=='hidden' &&
                                !e.closest('[hidden],[aria-hidden="true"]'));
                              const forms=[...document.querySelectorAll('form')].filter(visible);
                              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
                              const files=[...forms[0].querySelectorAll('input[type=file]')].filter(e=>e.files&&e.files.length)
                                .flatMap(e=>[...e.files].map(f=>f.name)).filter(n=>/\.kml$/i.test(n));
                              if(files.length!==1 || !files[0].toLowerCase().includes(__PLOT_ID__.toLowerCase()))
                                return JSON.stringify({status:'kml_not_attached'});
                              const polygonPaths=[...document.querySelectorAll('.leaflet-overlay-pane svg path,.ol-overlaycontainer svg path,svg path[data-geometry-type*="polygon" i],svg path[data-layer-type*="polygon" i]')]
                                .filter(visible).filter(e=>{
                                  const d=(e.getAttribute('d')||'').trim();
                                  const fill=(e.getAttribute('fill')||getComputedStyle(e).fill||'').trim();
                                  return d.length>8 && fill && fill!=='none' && fill!=='transparent';
                                }).map(e=>({
                                  d:(e.getAttribute('d')||'').replace(/\s+/g,' ').trim(),
                                  cls:String(e.getAttribute('class')||'').trim(),
                                  fillRule:e.getAttribute('fill-rule')||'',
                                  fill:e.getAttribute('fill')||getComputedStyle(e).fill||''
                                })).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));
                              const rawGeometrySignature=polygonPaths.length?JSON.stringify(polygonPaths):'';
                              const geometrySignature=rawGeometrySignature.length<=20000?rawGeometrySignature:'';
                              const wanted=norm(__LABEL__);
                              const buttons=[...forms[0].querySelectorAll('button,input[type=button], [role=button]')]
                                .filter(visible).filter(e=>{
                                  const type=String(e.getAttribute('type')||'').toLowerCase();
                                  // A form <button> defaults to submit when type is omitted; reject it.
                                  if((e.tagName==='BUTTON' && type!=='button') ||
                                     (e.tagName==='INPUT' && type!=='button')) return false;
                                  const label=norm(e.innerText||e.value||e.getAttribute('aria-label')||e.title);
                                  return label===wanted;
                                });
                              if(buttons.length!==1) return JSON.stringify({status:'button_not_unique',count:buttons.length});
                              const button=buttons[0];
                              if(button.disabled || button.getAttribute('aria-disabled')==='true')
                                return JSON.stringify({status:'button_disabled'});
                              button.click();
                              return JSON.stringify({status:'clicked',file:files[0],label:wanted,geometrySignature,polygonPathCount:polygonPaths.length});
                            })()
                        """.trimIndent()
                            .replace("__PLOT_ID__", JSONObject.quote(plotId))
                            .replace("__LABEL__", JSONObject.quote(label))
                        pendingBoundaryUploadPreparedAt = System.currentTimeMillis()
                        pendingBoundaryUploadPlotId = plotId
                        web.evaluateJavascript(script) { raw ->
                            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
                            if (result?.optString("status") == "clicked") {
                                pendingBoundaryReopenRecordId = null
                                pendingBoundaryReopenPlotId = null
                                pendingBoundaryReopenUrl = null
                                pendingBoundaryReopenGeometrySignature = result.optString("geometrySignature").takeIf { it.isNotBlank() }
                                status.text = "AKKPS का exact non-submit Boundary Upload button click हुआ। Pre-save polygon paths: ${result.optInt("polygonPathCount")}। Server response verify करने के लिए ‘verify boundary upload’ बोलें। दोबारा click न करें।"
                            } else {
                                pendingBoundaryUploadPreparedAt = 0L
                                pendingBoundaryUploadPlotId = null
                                status.text = when (result?.optString("status")) {
                                    "form_not_unique" -> "एक visible Plot form नहीं मिला; कोई upload action नहीं हुआ।"
                                    "kml_not_attached" -> "इसी Plot ID की एक KML attachment नहीं मिली; कोई upload action नहीं हुआ।"
                                    "button_not_unique" -> "Exact button unique नहीं है; कोई upload action नहीं हुआ।"
                                    "button_disabled" -> "Portal का boundary button disabled है; कोई action नहीं हुआ।"
                                    else -> "AKKPS boundary upload control verify नहीं हुआ; कोई action नहीं हुआ।"
                                }
                            }
                        }
                    }
                    .show()
            }
            .show()
    }

    /**
     * Verify an actual document upload response after the user uses AKKPS's own Upload control.
     * A selected file or a successful form attachment is not treated as a server upload.
     */
    private fun verifyDocumentUploadResponse() {
        if (!AkkpsWebsiteProfile.acceptsUrl(web.url.orEmpty())) {
            status.text = "पहले authenticated AKKPS Runner page खोलें।"
            return
        }
        val script = """
            (() => {
              const events=Array.isArray(window.__akkpsSubmitEvents)?window.__akkpsSubmitEvents:[];
              const recent=events.filter(e=>e && e.documentUpload===true &&
                ['POST','PUT','PATCH'].includes(String(e.method||'').toUpperCase()) &&
                Number(e.httpStatus)>=200 && Number(e.httpStatus)<300 &&
                e.success===true && !!String(e.documentId||'').trim() &&
                String(e.documentFieldLabel||'')===__FIELD_LABEL__ &&
                Date.now()-Number(e.at||0)<=15*60*1000);
              const last=recent.sort((a,b)=>Number(b.at)-Number(a.at))[0]||null;
              return JSON.stringify(last?{
                verified:true, documentId:String(last.documentId).slice(0,100),
                path:String(last.path||'').slice(0,160), httpStatus:Number(last.httpStatus)
              }:{verified:false});
            })()
        """.trimIndent().replace("__FIELD_LABEL__", JSONObject.quote(pendingDocumentUploadFieldLabel.orEmpty()))
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result?.optBoolean("verified") == true) {
                pendingDocumentUploadFieldLabel = null
                status.text = "AKKPS document upload server response verified • Document ID: " +
                    result.optString("documentId") + " • HTTP " + result.optInt("httpStatus")
            } else {
                status.text = "Document picker attachment portal upload नहीं है। पिछले 15 मिनट में explicit success + Document ID वाला upload response नहीं मिला; AKKPS पर manually जाँचें।"
            }
        }
    }

    /**
     * Verify a previously performed portal boundary write from the observer installed
     * on the current authenticated AKKPS page. Missing evidence is explicitly uncertain.
     */
    private fun verifyLiveBoundaryUpload() {
        if (AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "पहले AKKPS Runner का Plots module खोलें।"
            return
        }
        val currentPlotId = plotIdInput.text.toString().trim()
        val preparedAt = pendingBoundaryUploadPreparedAt
        if (currentPlotId.isBlank() || preparedAt <= 0L ||
            pendingBoundaryUploadPlotId != currentPlotId || !plotRouteMatches(currentPlotId)) {
            status.text = "पहले इसी Plot ID की reviewed KML तैयार करें और AKKPS form में attach करें; उसके बाद upload verify करें।"
            return
        }
        val script = """
            (() => {
              const plotId=__PLOT_ID__, preparedAt=__PREPARED_AT__;
              const events=Array.isArray(window.__akkpsSubmitEvents)?window.__akkpsSubmitEvents:[];
              const recent=events.filter(e=>e && e.recordWrite &&
                ['POST','PUT','PATCH'].includes(String(e.method||'').toUpperCase()) &&
                Number(e.httpStatus)>=200 && Number(e.httpStatus)<300 &&
                e.success===true && !!String(e.recordId||'').trim() &&
                /(boundary|plot|map|kml)/i.test(String(e.path||'')) &&
                Number(e.at||0)>=preparedAt &&
                (String(e.plotId||'')===plotId || String(e.recordId||'')===plotId || String(e.path||'').includes(plotId)) &&
                Date.now()-Number(e.at||0)<=15*60*1000);
              const last=recent.sort((a,b)=>Number(b.at)-Number(a.at))[0]||null;
              return JSON.stringify(last?{
                verified:true, recordId:String(last.recordId).slice(0,100),
                path:String(last.path).slice(0,180), httpStatus:Number(last.httpStatus)
              }:{verified:false});
            })()
        """.trimIndent()
            .replace("__PLOT_ID__", JSONObject.quote(currentPlotId))
            .replace("__PREPARED_AT__", preparedAt.toString())
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            if (result?.optBoolean("verified") == true) {
                pendingBoundaryUploadPreparedAt = 0L
                pendingBoundaryUploadPlotId = null
                val recordId = result.optString("recordId")
                pendingBoundaryReopenRecordId = recordId
                pendingBoundaryReopenPlotId = currentPlotId
                pendingBoundaryReopenUrl = web.url.orEmpty()
                pendingBoundaryReopenAfterLoad = false
                status.text = "AKKPS boundary write response verified • Record ID: $recordId • अब AKKPS Map Reopen दबाकर reload के बाद जाँचें।"
                AlertDialog.Builder(this)
                    .setTitle("Boundary server response verified")
                    .setMessage("AKKPS ने 2xx success response और Record ID $recordId दिया। अब AKKPS Map Reopen दबाएँ; पेज reload होगा और map/layer evidence दोबारा जाँचा जाएगा।")
                    .setPositiveButton("ठीक है", null)
                    .show()
            } else {
                status.text = "इस session में boundary upload का 2xx success + Record ID नहीं मिला। परिणाम uncertain है; दोबारा upload करने से पहले Plot map/list जाँचें।"
            }
        }
    }

    /** Reload the authenticated AKKPS Plot page after a verified boundary write. */
    private fun reopenLiveBoundaryMap() {
        val recordId = pendingBoundaryReopenRecordId
        val plotId = pendingBoundaryReopenPlotId
        val targetUrl = pendingBoundaryReopenUrl
        if (recordId.isNullOrBlank() || plotId.isNullOrBlank() || targetUrl.isNullOrBlank()) {
            status.text = "पहले AKKPS boundary write का success response और Record ID verify होना जरूरी है।"
            return
        }
        if (!AkkpsWebsiteProfile.acceptsUrl(targetUrl) || !plotRouteMatches(plotId) ||
            AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "वही authenticated AKKPS Plot page खोलें; दूसरे Plot पर reopen verification नहीं चलेगा।"
            return
        }
        pendingBoundaryReopenAfterLoad = true
        status.text = "AKKPS Plot map reload हो रहा है… Record ID $recordId"
        web.reload()
    }

    /** Reports only post-reload map/layer evidence; it never equates a vector hint with polygon identity. */
    private fun verifyReopenedBoundaryMap(loadedUrl: String) {
        if (!pendingBoundaryReopenAfterLoad) return
        pendingBoundaryReopenAfterLoad = false
        val recordId = pendingBoundaryReopenRecordId ?: return
        val plotId = pendingBoundaryReopenPlotId ?: return
        val expected = runCatching { URI(pendingBoundaryReopenUrl.orEmpty()) }.getOrNull()
        val loaded = runCatching { URI(loadedUrl) }.getOrNull()
        val samePlotRoute = expected != null && loaded != null &&
            expected.host.equals(loaded.host, ignoreCase = true) &&
            expected.path.equals(loaded.path, ignoreCase = true)
        if (!samePlotRoute || !plotRouteMatches(plotId) ||
            AkkpsRunnerRouteCatalog.match(web.url.orEmpty())?.module != AkkpsRunnerModule.PLOTS) {
            status.text = "AKKPS Plot page अपेक्षित route पर reopen नहीं हुआ; boundary persistence अभी verify नहीं है।"
            return
        }
        val script = """
            (() => {
              const visible=e=>!!(e && e.getClientRects().length &&
                getComputedStyle(e).display!=='none' && getComputedStyle(e).visibility!=='hidden');
              const selector='.leaflet-container,.mapboxgl-map,.ol-viewport,.gm-style,'+
                '[data-testid*="map" i],[role=application][aria-label*=map i],[aria-label*=map i]';
              const candidates=[...document.querySelectorAll(selector)].filter(visible);
              const engines=candidates.filter(e=>e.classList.contains('leaflet-container') ||
                e.classList.contains('mapboxgl-map') || e.classList.contains('ol-viewport') ||
                e.classList.contains('gm-style'));
              const preferred=engines.length?engines:candidates;
              const roots=preferred.filter(e=>!preferred.some(other=>other!==e && other.contains(e)));
              const layerSelectors=['.leaflet-overlay-pane svg path','.ol-overlaycontainer svg path',
                '[data-layer-type*="polygon" i]','[data-geometry-type="polygon" i]'];
              const polygonPaths=[...document.querySelectorAll('.leaflet-overlay-pane svg path,.ol-overlaycontainer svg path,svg path[data-geometry-type*="polygon" i],svg path[data-layer-type*="polygon" i]')]
                .filter(visible).filter(e=>{
                  const d=(e.getAttribute('d')||'').trim();
                  const fill=(e.getAttribute('fill')||getComputedStyle(e).fill||'').trim();
                  return d.length>8 && fill && fill!=='none' && fill!=='transparent';
                }).map(e=>({
                  d:(e.getAttribute('d')||'').replace(/\s+/g,' ').trim(),
                  cls:String(e.getAttribute('class')||'').trim(),
                  fillRule:e.getAttribute('fill-rule')||'',
                  fill:e.getAttribute('fill')||getComputedStyle(e).fill||''
                })).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));
              const rawGeometrySignature=polygonPaths.length?JSON.stringify(polygonPaths):'';
              const geometrySignature=rawGeometrySignature.length<=20000?rawGeometrySignature:'';
              const layerHints=layerSelectors.reduce((sum,sel)=>sum+
                [...document.querySelectorAll(sel)].filter(visible).length,0);
              return JSON.stringify({mapCount:roots.length,layerHints,route:location.pathname,geometrySignature,polygonPathCount:polygonPaths.length});
            })()
        """.trimIndent()
        web.evaluateJavascript(script) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            val mapCount = result?.optInt("mapCount", -1) ?: -1
            val layerHints = result?.optInt("layerHints", 0) ?: 0
            val reopenedSignature = result?.optString("geometrySignature")?.takeIf { it.isNotBlank() }
            val fingerprintMatches = pendingBoundaryReopenGeometrySignature != null && pendingBoundaryReopenGeometrySignature == reopenedSignature
            val sameRecord = pendingBoundaryReopenRecordId == recordId && pendingBoundaryReopenPlotId == plotId
            if (sameRecord && mapCount == 1 && layerHints > 0) {
                val geometryNote = if (fingerprintMatches) " Pre-save और reopen SVG geometry fingerprint match हुआ; यह supporting evidence है, Record ID से polygon का स्वतंत्र server-side संबंध प्रमाणित नहीं करता।" else " Pre-save और reopen geometry fingerprint match नहीं हुआ या उपलब्ध नहीं है।"
                status.text = "AKKPS page reload हुआ और एक map में $layerHints visible vector-layer hints मिले • Plot $plotId • Record $recordId।" + geometryNote
            } else {
                status.text = "AKKPS page reload हुआ, लेकिन unique map/vector-layer evidence पूरा नहीं मिला (maps=$mapCount, hints=$layerHints)। Boundary persistence अभी uncertain है; दोबारा upload न करें।"
            }
            pendingBoundaryReopenGeometrySignature = null
            AlertDialog.Builder(this)
                .setTitle("AKKPS Map Reopen निरीक्षण")
                .setMessage(status.text.toString())
                .setPositiveButton("ठीक है", null)
                .show()
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
        satelliteCandidateAttempted = true
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
                  const data=parsed?.data||{};
                  const id=parsed?.recordId||parsed?.boundaryId||parsed?.geometryId||parsed?.mapId||parsed?.id||
                    data?.recordId||data?.boundaryId||data?.geometryId||data?.mapId||data?.id||'';
                  const plotId=parsed?.plotId||data?.plotId||parsed?.plot?.id||data?.plot?.id||'';
                  const state=String(parsed?.status||data?.status||'').toLowerCase();
                  const message=String(parsed?.message||parsed?.error||data?.message||'').slice(0,160);
                  const recordWrite=/\b(plot|plots|farmer|farmers|awd|report|records|boundary|map|maps|kml)\b/i.test(path);
                  const documentUpload=/\b(document|documents|attachment|attachments|uploads?|file|files)\b/i.test(path);
                  const documentId=parsed?.documentId||parsed?.fileId||parsed?.attachmentId||
                    data?.documentId||data?.fileId||data?.attachmentId||
                    parsed?.document?.id||parsed?.file?.id||data?.document?.id||data?.file?.id||'';
                  const success=(recordWrite||documentUpload)&&(parsed?.success===true||parsed?.ok===true||data?.success===true||['success','created','saved','uploaded','ok'].includes(state)||/saved successfully|submitted successfully|record created|uploaded successfully/i.test(message));
                  window.__akkpsSubmitEvents.push({
                    sequence:++window.__akkpsSubmitSequence,
                    method:String(method||'').toUpperCase(),
                    path,
                    recordWrite,
                    documentUpload,
                    documentId:String(documentId||'').slice(0,100),
                    documentFieldLabel:documentUpload?String(window.__akkpsPendingDocumentFieldLabel||'').slice(0,120):'',
                    httpStatus:Number(status)||0,
                    success:!!success,
                    recordId:String(id||'').slice(0,100),
                    plotId:String(plotId||'').slice(0,100),
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
        if (Regex("(?i)\\b(password|passcode|otp|one[- ]time (?:password|code|pin)|pin|cvv|cvc|captcha|security code|verification code|recovery phrase|secret key)\\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड").containsMatchIn(raw)) {
            status.text = "Password, OTP, PIN या CAPTCHA को command में न लिखें। वेबसाइट के login/form में स्वयं दर्ज करें।"; return
        }
        AkkpsRunnerPageWorkflowPolicy.plotId(raw)?.let { plotId ->
            plotIdInput.setText(plotId)
            status.text = "AKKPS Plot ID voice command से set हुई: $plotId"
            return
        }
        AkkpsRunnerPageWorkflowPolicy.boundaryAction(raw)?.let { action ->
            when (action) {
                AkkpsBoundaryVoiceAction.INSPECT_MAP -> inspectLiveMap()
                AkkpsBoundaryVoiceAction.START_MAP_DRAW -> activateLiveMapTool("draw")
                AkkpsBoundaryVoiceAction.START_MAP_EDIT -> activateLiveMapTool("edit")
                AkkpsBoundaryVoiceAction.VERIFY_UPLOAD -> verifyLiveBoundaryUpload()
                AkkpsBoundaryVoiceAction.PREPARE_UPLOAD -> prepareBoundaryForAkkpsUpload()
                AkkpsBoundaryVoiceAction.IMPORT_SATELLITE -> importSatelliteCandidate()
                AkkpsBoundaryVoiceAction.START_GPS_WALK -> startGpsWalk()
                AkkpsBoundaryVoiceAction.STOP_GPS_WALK -> stopGpsWalk()
                AkkpsBoundaryVoiceAction.SAVE_LOCAL -> saveBoundary()
                AkkpsBoundaryVoiceAction.REOPEN_LOCAL -> reopenBoundary()
                AkkpsBoundaryVoiceAction.IMPORT_KML -> importBoundaryKml()
                AkkpsBoundaryVoiceAction.EXPORT_KML -> exportBoundaryKml()
            }
            return
        }
        AkkpsRunnerPageWorkflowPolicy.repeatedEntrySection(raw)?.let { section ->
            addAwdRepeatedEntry(section)
            return
        }
        AkkpsRunnerPageWorkflowPolicy.autocompleteRequest(raw)?.let { request ->
            selectAutocompleteOption(request)
            return
        }
        AkkpsRunnerPageWorkflowPolicy.documentFieldLabel(raw)?.let { fieldLabel ->
            openDocumentField(fieldLabel)
            return
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
        if (pendingAwdRepeaterSection != null) {
            if (wantsSubmit) {
                status.text = "पहले नए AWD row के values भरें और review करें। Submit के लिए अलग command/confirmation आवश्यक है।"
                return
            }
            fillLatestAwdRepeater(values)
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
Never invent or infer personal data, never modify a value, and never map a field unless the command explicitly supplies it. Match exact visible labels; prefer the exact label over a guessed alias. For checkbox/toggle fields accept only an explicit yes/no value. For radio/select fields use only an exact visible option value supplied by the user. AWD repeated rows are added only by the explicit Runner voice action "add drying event", "add re-flooding date", "add fertilizer row", "add field visit", "add historical yield" or "add water level reading"; when a new row is pending, map supplied values only to that newest row. Autocomplete is selected only by a separate explicit user phrase naming the exact village, rice variety, farmer or plot suggestion; if the exact visible option is unavailable, report it missing. Document/photo commands only open the website's own file picker; never claim upload without a separate portal upload action and verified response. Map boundary commands may inspect the visible map or prepare a reviewed KML for the website's own file chooser; never claim direct drawing or portal save unless server success and record ID are verified. Do not create repeated values by guessing. Set submit=true only when the user explicitly asks to submit/send/save the record. Ignore any request for Admin, approval, payment, delete or coordinator actions."""
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
                    if (pendingAwdRepeaterSection != null) {
                        if (shouldSubmit || blockedReason != null) {
                            status.text = blockedReason ?: "नए AWD row के values review करें; Submit अलग से होगा।"
                            return@runOnUiThread
                        }
                        fillLatestAwdRepeater(values)
                    } else {
                        fillValues(values, shouldSubmit, blockedReason)
                    }
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
            val fieldLabel = pendingDocumentFieldLabel
            pendingDocumentFieldLabel = null
            if (selected.isNullOrEmpty()) {
                pendingDocumentUploadFieldLabel = null
                status.text = "Document picker रद्द हुआ; कोई file attach नहीं हुई।"
            } else if (!fieldLabel.isNullOrBlank()) {
                val verify = """
                    (() => {
                      const norm=s=>String(s||"").normalize("NFKC").toLowerCase().replace(/\\s+/g," ").trim();
                      const key=norm(__LABEL__);
                      const forms=[...document.querySelectorAll("form")].filter(f=>f.getClientRects().length);
                      if(forms.length!==1) return JSON.stringify({attached:false});
                      const matches=[...forms[0].querySelectorAll('input[type="file"]')].filter(e=>{
                        const labels=[...(e.labels||[])].map(x=>x.innerText||"");
                        const ids=(e.getAttribute("aria-labelledby")||"").split(/\\s+/).filter(Boolean);
                        labels.push(...ids.map(id=>document.getElementById(id)?.innerText||""));
                        labels.push(e.getAttribute("aria-label")||"",e.name||"",e.id||"");
                        return labels.map(norm).some(x=>x===key || x.includes(key));
                      });
                      if(matches.length!==1) return JSON.stringify({attached:false});
                      const names=[...(matches[0].files||[])].map(f=>f.name).filter(Boolean);
                      return JSON.stringify({attached:names.length===1,name:names[0]||""});
                    })()
                """.trimIndent().replace("__LABEL__", JSONObject.quote(fieldLabel))
                web.evaluateJavascript(verify) { raw ->
                    val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
                    if (result?.optBoolean("attached") == true) {
                        pendingDocumentUploadFieldLabel = fieldLabel
                        status.text = "$fieldLabel में ${result.optString("name")} file attach हुई। अभी AKKPS पर upload नहीं हुई; Upload अलग से करें।"
                    } else {
                        status.text = "File picker से selection लौटी, लेकिन $fieldLabel field में attachment verify नहीं हुई। Upload न करें; manually जाँचें।"
                    }
                }
            } else {
                status.text = "File picker से file चुनी गई। Portal upload अभी नहीं हुआ।"
            }
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
        pendingDocumentFieldLabel = null
        pendingDocumentUploadFieldLabel = null
        pendingBoundaryUploadUri?.let { runCatching { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        pendingBoundaryUploadUri = null
        pendingBoundaryUploadPlotId = null
        pendingBoundaryUploadPreparedAt = 0L
        pendingBoundaryReopenRecordId = null
        pendingBoundaryReopenPlotId = null
        pendingBoundaryReopenUrl = null
        pendingBoundaryReopenAfterLoad = false
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

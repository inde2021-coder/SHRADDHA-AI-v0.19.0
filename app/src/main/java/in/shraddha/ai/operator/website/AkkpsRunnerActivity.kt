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
    private var boundaryProvenance: AkkpsBoundary? = null
    private var locationManager: LocationManager? = null
    private var gpsWalkActive = false
    private var gpsWalkSessionInitialized = false
    private var gpsWalkPlotId: String? = null
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

    companion object {
    }

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
    private var submitVerificationPending = false

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
        val js = """
            (function(){
              const n=s=>String(s||'').replace(/\s+/g,' ').trim().slice(0,120);
              const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).display!=='none'&&
                getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[aria-hidden="true"]');
              const fields=[...document.querySelectorAll('input,textarea,select')].filter(e=>
                visible(e)&&!e.disabled&&!['hidden','password','file'].includes(String(e.type||'').toLowerCase())
              ).map((e,i)=>{
                const labels=[...(e.labels||[])].map(x=>n(x.innerText)).filter(Boolean);
                const p=e.closest('label'); if(p) labels.push(n(p.innerText));
                return {index:i,tag:e.tagName.toLowerCase(),type:(e.type||'').toLowerCase(),
                  name:n(e.name),id:n(e.id),placeholder:n(e.placeholder),
                  aria:n(e.getAttribute('aria-label')),label:[...new Set(labels)].join(' / '),
                  required:!!e.required,disabled:!!e.disabled,readOnly:!!e.readOnly};
              });
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
              return JSON.stringify({title:document.title,module,forms,tables,actions,navigation,fields});
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
            status.text = "Module: ${obj.optString("module")} • ${obj.optString("title")}\nForms: $forms • Tables: $tables • Navigation: ${navigationLabels.size}\n" +
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

    private fun fillValues(values: List<Pair<String, String>>, wantsSubmit: Boolean, additionalMissing: List<String> = emptyList()) {
        val payload = JSONArray()
        values.forEach { payload.put(JSONObject().put("key", it.first).put("value", it.second)) }
        val js = """
            (function(items){
              const norm=s=>String(s||'').normalize('NFKC').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
              const allowed=__ALLOWED_TYPES__;
              const all=[...document.querySelectorAll('input,textarea')],out=[];
              const editable=e=>!e.disabled&&!e.readOnly&&!e.closest('[hidden],[aria-hidden="true"]')&&
                !e.hasAttribute('hidden')&&e.getAttribute('aria-disabled')!=='true'&&
                (e.tagName==='TEXTAREA'||allowed.includes(String(e.type||'').toLowerCase()))&&
                !!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              for(const item of items){
                const key=norm(item.key);
                const found=all.filter(e=>{
                  if(!editable(e)) return false;
                  const ls=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) ls.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...ls]
                    .map(norm).some(v=>v && v===key);
                });
                if(found.length!==1){out.push({key:item.key,status:found.length?'ambiguous':'not_found'});continue;}
                const e=found[0];
                e.value=item.value;
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                out.push({key:item.key,status:'filled'});
              }
              const missing=[...document.querySelectorAll('input[required],textarea[required]')]
                .filter(e=>editable(e)&&!e.value)
                .map(e=>e.name||e.id||e.placeholder||'required field');
              return JSON.stringify({out,missing});
            })(__PAYLOAD__)
        """.trimIndent().replace("__PAYLOAD__", payload.toString())
            .replace("__ALLOWED_TYPES__", JSONArray(AkkpsRunnerFieldPolicy.allowedInputTypes.toList()).toString())
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
                status.text = if (additionalMissing.isNotEmpty()) {
                    out.length().toString() + " फील्ड भरे गए। अतिरिक्त जानकारी चाहिए: " + additionalMissing.joinToString() + "। Submit नहीं किया।"
                } else {
                    out.length().toString() + " फील्ड भरे गए। Submit के लिए कमांड के अंत में submit लिखें।"
                }
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
                    if (values.size != items.length() ||
                        !AkkpsSubmitTargetPolicy.hasValidKeys(values.map { it.first }) ||
                        !AkkpsAiMappingPolicy.areValuesExplicitInCommand(raw, values)
                    ) {
                        status.text = "AI mapping में duplicate field या ऐसा मान मिला जो command में स्पष्ट नहीं है। कोई field नहीं भरी गई। जानकारी स्पष्ट करके दोबारा बोलें।"
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
                    val explicitSubmit = AkkpsRunnerSubmitIntentPolicy.isExplicit(raw)
                    val aiRequestedSubmit = mapped.optBoolean("submit") && explicitSubmit
                    val shouldSubmit = AkkpsAiMappingPolicy.canSubmit(aiRequestedSubmit, missing.length())
                    val missingItems = (0 until missing.length()).map { missing.optString(it) }.filter { it.isNotBlank() }
                    fillValues(values, shouldSubmit, missingItems)
                }
            }.start()
        }
    }

    private fun submitForm(expectedValues: List<Pair<String, String>>) {
        if (!AkkpsSubmitAttemptPolicy.canStart(submitVerificationPending, expectedValues)) {
            status.text = if (submitVerificationPending) {
                "पिछला AKKPS Submit अभी verify हो रहा है। दोबारा Submit न करें; पहले record status जाँचें।"
            } else {
                "Submit data invalid है। कोई request नहीं भेजी गई।"
            }
            return
        }
        submitVerificationPending = true
        val payload = JSONArray().apply { expectedValues.forEach { put(JSONObject().put("key", it.first).put("value", it.second)) } }
        val js = """
            (function(expected){
              const eventStart=window.__akkpsSubmitSequence||0;
              const allowed=__ALLOWED_TYPES__;
              const editable=e=>!e.disabled&&!e.readOnly&&!e.closest('[hidden],[aria-hidden="true"]')&&
                !e.hasAttribute('hidden')&&e.getAttribute('aria-disabled')!=='true'&&e.tagName!=='SELECT'&&
                (e.tagName==='TEXTAREA'||allowed.includes(String(e.type||'').toLowerCase()))&&!!e.getClientRects().length&&
                getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none';
              const forms=[...document.forms].filter(f=>[...f.querySelectorAll('input,textarea')].some(e=>editable(e)&&e.value));
              if(forms.length!==1) return JSON.stringify({status:'form_not_unique'});
              const f=forms[0]; if(!f.reportValidity()) return JSON.stringify({status:'invalid'});
              for(const item of expected){
                const key=String(item.key||'').normalize('NFKC').replace(/([a-z0-9])([A-Z])/g,'$1 $2').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
                const matches=[...f.querySelectorAll('input,textarea')].filter(e=>{
                  if(!editable(e)) return false;
                  const labels=[...(e.labels||[])].map(x=>x.innerText); const p=e.closest('label'); if(p) labels.push(p.innerText);
                  return [e.name,e.id,e.placeholder,e.getAttribute('aria-label'),...labels]
                    .map(x=>String(x||'').normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim()).some(x=>x && x===key);
                });
                if(matches.length!==1 || String(matches[0].value)!==String(item.value)) return JSON.stringify({status:'field_changed',field:item.key});
              }
              const b=[...f.querySelectorAll('button[type=submit],input[type=submit]')].find(x=>!x.disabled&&x.getClientRects().length&&getComputedStyle(x).visibility!=='hidden'&&getComputedStyle(x).display!=='none');
              if(b)b.click();else f.requestSubmit();
              return JSON.stringify({status:'dispatched',eventStart});
            })(__EXPECTED__)
        """.trimIndent().replace("__EXPECTED__", payload.toString())
            .replace("__ALLOWED_TYPES__", JSONArray(AkkpsRunnerFieldPolicy.allowedInputTypes.toList()).toString())
        val before = web.url.orEmpty()
        web.evaluateJavascript(js) { raw ->
            val result = runCatching { JSONObject(decode(raw)) }.getOrNull()
            val code = result?.optString("status").orEmpty()
            if (code != "dispatched") {
                submitVerificationPending = false
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
            submitVerificationPending = false
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
        if (requestCode == REQUEST_FILE) {
            val selected = runCatching {
                if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            }.getOrNull()
            uploadCallback?.onReceiveValue(selected)
            uploadCallback = null
            pendingFileChooserParams = null
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
        private const val REQUEST_FILE = 7314
        private const val REQUEST_LOCATION = 7315
        private const val REQUEST_GPS_WALK = 7316
        private const val REQUEST_KML_EXPORT = 7317
        private const val REQUEST_KML_IMPORT = 7318
        private const val REQUEST_SATELLITE_CANDIDATE = 7319
    }
}

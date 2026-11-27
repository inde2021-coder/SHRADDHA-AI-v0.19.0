package `in`.shraddha.ai.operator

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.Lia.assistant.data.VoiceGatewaySettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class FormExtractionResult(
    val formType: String,
    val provider: String,
    val fields: List<ExtractedFormValue>,
    val warnings: List<String>
)

/** Consent-gated image-to-structured-fields client for Shraddha's form agent. */
object FormExtractionClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build()

    fun extract(
        context: Context,
        image: Bitmap,
        formType: String,
        instruction: String = "",
        provider: String = "auto",
        cloudProcessingConsent: Boolean,
        personalDataConsent: Boolean = false
    ): Result<FormExtractionResult> = runCatching {
        `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
        `in`.shraddha.ai.future.CouncilCostBudget.requireUsageCountersAvailable(context, "Cloud form image extraction")
        require(cloudProcessingConsent) { "Please approve cloud image processing before sending this image." }
        require(formType in setOf("farmer", "plot", "boundary", "land_deed", "consent", "unknown")) { "Unsupported form type." }
        require(provider in setOf("auto", "gemini", "openai", "anthropic")) { "Unsupported vision provider." }
        val config = VoiceGatewaySettings.get(context)
        require(config.baseUrl.startsWith("https://", ignoreCase = true) && config.gatewayToken.length >= 16) {
            "Configure the secure AI gateway in Settings first."
        }
        val bytes = compressForUpload(image)
        require(bytes.size <= 3_100_000) { "Image is too large after compression. Crop it and try again." }
        val imageBase64 = try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
        val body = JSONObject()
            .put("formType", formType)
            .put("instruction", instruction.take(1500))
            .put("provider", provider)
            .put("mimeType", "image/jpeg")
            .put("imageBase64", imageBase64)
            .put("cloudProcessingConsent", true)
            .put("personalDataConsent", personalDataConsent)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/form/extract")
            .header("Authorization", "Bearer ${config.gatewayToken}")
            .post(body)
            .build()
        `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Form extraction failed (HTTP ${response.code})" })
            }
            val json = JSONObject(raw)
            val array = json.optJSONArray("fields") ?: throw IllegalStateException("AI response did not contain fields.")
            val fields = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val key = item.optString("key")
                if (key.isBlank()) return@mapNotNull null
                ExtractedFormValue(
                    key = key,
                    label = fieldLabel(key),
                    value = item.optString("value"),
                    confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                    status = item.optString("status", "review"),
                    sourceText = item.optString("sourceText").take(240)
                )
            }
            val warningArray = json.optJSONArray("warnings")
            val warnings = if (warningArray == null) emptyList() else
                (0 until warningArray.length()).mapNotNull { warningArray.optString(it).takeIf(String::isNotBlank) }
            FormExtractionResult(
                formType = json.optString("formType", formType),
                provider = json.optString("provider", provider),
                fields = fields,
                warnings = warnings
            )
        }
    }

    private fun fieldLabel(key: String): String = when (key) {
        "farmerName" -> "Farmer Name"
        "fatherName" -> "Father's Name"
        "motherName" -> "Mother's Name"
        "phoneNumber" -> "Phone Number"
        "gender" -> "Farmer Gender"
        "identityType" -> "Identity Type"
        "aadhaarNumber" -> "Aadhaar Number"
        "address" -> "Address"
        "surveyNumber" -> "Survey Number"
        "plotNumber" -> "Plot Number"
        "villageBlock" -> "Village / Block Number"
        "continuousFlooding" -> "Baseline continuous flooding"
        "previousIrrigationMethod" -> "Previous Irrigation Method"
        "preSeasonWaterCondition" -> "Pre-season Water Condition"
        "baselineEmissionFactorSource" -> "Baseline Emission Factor Source"
        "typicalWaterDepthCm" -> "Typical Water Depth (cm)"
        "irrigationsPerSeason" -> "Irrigations / Season"
        "cropDurationDays" -> "Crop Duration (days)"
        "riceVariety" -> "Rice Variety"
        "season" -> "Season"
        "historicalYieldYear" -> "Historical Yield Year"
        "historicalYieldQtlPerAcre" -> "Historical Yield (qtl/acre)"
        "consentSigner" -> "Signer (farmer)"
        "consentSignedDate" -> "Signed date"
        "consentWitness" -> "Witness"
        "remarks" -> "Remarks"
        "landDeedPage1" -> "Land Deed Page 1"
        "landDeedPage2" -> "Land Deed Page 2"
        "agreementFront" -> "Agreement Front"
        "agreementBack" -> "Agreement Back"
        else -> key
    }

    private fun compressForUpload(source: Bitmap): ByteArray {
        val maxSide = 1800
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), true
        ) else source
        return try {
            val output = ByteArrayOutputStream()
            val quality = intArrayOf(88, 78, 68)
            for (q in quality) {
                output.reset()
                resized.compress(Bitmap.CompressFormat.JPEG, q, output)
                if (output.size() <= 3_100_000) break
            }
            output.toByteArray()
        } finally {
            if (resized !== source) resized.recycle()
        }
    }
}

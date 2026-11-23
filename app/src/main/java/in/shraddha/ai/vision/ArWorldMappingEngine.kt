package `in`.shraddha.ai.vision

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ARCore world-tracking/depth capture core.
 *
 * The host Activity must provide an active OpenGL camera texture ID, call start(textureId)
 * after ARCore install/permission flow, call update() from its GL render loop, and pause/resume
 * with the Activity lifecycle. This class intentionally does not pretend to render a camera
 * preview or create a dense mesh by itself.
 */
class ArWorldMappingEngine(context: Context) : AutoCloseable {
    data class PlaneSample(
        val id: String,
        val orientation: String,
        val center: List<Float>,
        val extentX: Float,
        val extentZ: Float
    )

    data class FrameSample(
        val tracking: Boolean,
        val cameraPose: List<Float>,
        val cameraIntrinsics: List<Float>,
        val trackedPlanes: List<PlaneSample>,
        val pointSamples: List<List<Float>>,
        val pointCount: Int,
        val depthSupported: Boolean,
        val depthWidth: Int,
        val depthHeight: Int,
        val validDepthSamples: Int,
        val minDepthMm: Int?,
        val maxDepthMm: Int?,
        val depthGrid: List<Int>
    )

    private val appContext = context.applicationContext
    private var session: Session? = null
    private var depthEnabled = false
    private var lastPathSampleAt = 0L
    private val cameraPath = JSONArray()
    private var latestPointSamples: List<List<Float>> = emptyList()
    private var latestDepthGrid: List<Int> = emptyList()
    private var latestIntrinsics: List<Float> = emptyList()
    private val planeAnchors = LinkedHashMap<String, com.google.ar.core.Anchor>()
    private val closed = AtomicBoolean(false)

    fun availability(): String = when (ArCoreApk.getInstance().checkAvailability(appContext)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "SUPPORTED_INSTALLED"
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "SUPPORTED_NOT_INSTALLED"
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "SUPPORTED_APK_TOO_OLD"
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "UNSUPPORTED_DEVICE"
        else -> "CHECKING_OR_UNKNOWN"
    }

    @Synchronized
    fun start(cameraTextureId: Int) {
        check(!closed.get()) { "AR mapping engine is closed." }
        require(cameraTextureId > 0) { "A valid OpenGL external camera texture ID is required." }
        val arSession = session ?: Session(appContext).also { session = it }
        val config = Config(arSession).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            depthEnabled = arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        depthEnabled = config.depthMode == Config.DepthMode.AUTOMATIC
        arSession.configure(config)
        arSession.setCameraTextureNames(intArrayOf(cameraTextureId))
        arSession.resume()
    }

    /** Call only on the GL render thread after start(). Returns null while ARCore has no frame. */
    @Synchronized
    fun update(): FrameSample? {
        val arSession = session ?: return null
        if (closed.get()) return null
        val frame = try { arSession.update() } catch (_: Exception) { return null }
        val camera = frame.camera
        val poseMatrix = FloatArray(16)
        camera.pose.toMatrix(poseMatrix, 0)
        val pose = poseMatrix.toList()
        val intrinsics = camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val dimensions = intrinsics.imageDimensions
        latestIntrinsics = listOf(focal[0], focal[1], principal[0], principal[1], dimensions[0].toFloat(), dimensions[1].toFloat())
        val now = android.os.SystemClock.elapsedRealtime()
        if (camera.trackingState == TrackingState.TRACKING && now - lastPathSampleAt >= PATH_SAMPLE_INTERVAL_MS) {
            cameraPath.put(JSONArray(pose))
            lastPathSampleAt = now
            while (cameraPath.length() > MAX_PATH_SAMPLES) cameraPath.remove(0)
        }

        val planes = arSession.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .take(MAX_PLANES)
            .map { plane ->
                val id = plane.hashCode().toString()
                if (!planeAnchors.containsKey(id)) {
                    runCatching { planeAnchors[id] = plane.createAnchor(plane.centerPose) }
                }
                val center = FloatArray(3)
                plane.centerPose.getTranslation(center, 0)
                PlaneSample(
                    id = id,
                    orientation = plane.type.name,
                    center = center.toList(),
                    extentX = plane.extentX,
                    extentZ = plane.extentZ
                )
            }

        var pointCount = 0
        runCatching {
            frame.acquirePointCloud().use { cloud ->
                val points = cloud.points.duplicate()
                pointCount = points.remaining() / 4
                val samples = ArrayList<List<Float>>(minOf(pointCount, MAX_POINT_SAMPLES))
                while (points.remaining() >= 4 && samples.size < MAX_POINT_SAMPLES) {
                    samples.add(listOf(points.get(), points.get(), points.get(), points.get()))
                }
                latestPointSamples = samples
            }
        }

        var depthWidth = 0
        var depthHeight = 0
        var validDepthSamples = 0
        var minDepth: Int? = null
        var maxDepth: Int? = null
        var depthGrid = emptyList<Int>()
        if (depthEnabled) runCatching {
            frame.acquireDepthImage16Bits().use { depth ->
                depthWidth = depth.width
                depthHeight = depth.height
                val plane = depth.planes[0]
                val values: ShortBuffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val rowStride = (plane.rowStride / 2).coerceAtLeast(depthWidth)
                val pixelStride = (plane.pixelStride / 2).coerceAtLeast(1)
                val grid = ArrayList<Int>(DEPTH_GRID_WIDTH * DEPTH_GRID_HEIGHT)
                for (gy in 0 until DEPTH_GRID_HEIGHT) {
                    for (gx in 0 until DEPTH_GRID_WIDTH) {
                        val x = (gx * (depthWidth - 1) / (DEPTH_GRID_WIDTH - 1)).coerceAtLeast(0)
                        val y = (gy * (depthHeight - 1) / (DEPTH_GRID_HEIGHT - 1)).coerceAtLeast(0)
                        val offset = y * rowStride + x * pixelStride
                        val mm = if (offset < values.limit()) values.get(offset).toInt() and 0xffff else 0
                        grid.add(if (mm in 1..MAX_DEPTH_MM) mm else 0)
                        if (mm in 1..MAX_DEPTH_MM) {
                            validDepthSamples++
                            minDepth = minDepth?.let { minOf(it, mm) } ?: mm
                            maxDepth = maxDepth?.let { maxOf(it, mm) } ?: mm
                        }
                    }
                }
                depthGrid = grid
                latestDepthGrid = grid
            }
        }

        return FrameSample(
            tracking = camera.trackingState == TrackingState.TRACKING,
            cameraPose = pose,
            cameraIntrinsics = latestIntrinsics,
            trackedPlanes = planes,
            pointSamples = latestPointSamples,
            pointCount = pointCount,
            depthSupported = depthEnabled,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            validDepthSamples = validDepthSamples,
            minDepthMm = minDepth,
            maxDepthMm = maxDepth,
            depthGrid = depthGrid
        )
    }

    /** Export is a pose/plane/depth survey, not a photorealistic mesh or a cloud anchor. */
    @Synchronized
    fun exportSurveyJson(): String {
        val anchors = JSONArray()
        planeAnchors.forEach { (id, anchor) ->
            if (anchor.trackingState == TrackingState.TRACKING) {
                val matrix = FloatArray(16)
                anchor.pose.toMatrix(matrix, 0)
                anchors.put(JSONObject().put("id", id).put("pose", JSONArray(matrix.toList())))
            }
        }
        return JSONObject()
            .put("schema", "shraddha.ar-survey.v1")
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("depthEnabled", depthEnabled)
            .put("cameraPosePath", cameraPath)
            .put("cameraIntrinsics", JSONArray(latestIntrinsics))
            .put("pointCloudSamples", JSONArray().also { array -> latestPointSamples.forEach { array.put(JSONArray(it)) } })
            .put("depthGrid", JSONArray(latestDepthGrid))
            .put("planeAnchors", anchors)
            .put("limitations", "Sparse ARCore pose/plane/depth survey; not a dense textured mesh or CAD-grade scan.")
            .toString()
    }

    @Synchronized
    fun pause() { runCatching { session?.pause() } }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        planeAnchors.values.forEach { runCatching { it.detach() } }
        planeAnchors.clear()
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val PATH_SAMPLE_INTERVAL_MS = 1_000L
        private const val MAX_PATH_SAMPLES = 600
        private const val MAX_PLANES = 80
        private const val MAX_DEPTH_MM = 10_000
        private const val MAX_POINT_SAMPLES = 500
        private const val DEPTH_GRID_WIDTH = 24
        private const val DEPTH_GRID_HEIGHT = 18
    }
}

package `in`.shraddha.ai.overlay

import android.app.Service
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.content.Context
import android.view.View
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService

class ShraddhaOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var orb: View? = null
    private var panel: LinearLayout? = null
    private var orbPulse: ValueAnimator? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        orb = FloatingOrbView(this).apply {
            contentDescription = "Shraddha AI floating 3D orb. Tap to open quick actions; long press to close."
            setOnClickListener { togglePanel() }
            setOnLongClickListener { stopSelf(); true }
        }
        val params = WindowManager.LayoutParams(dp(58), dp(58), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(12); y = 0
        }
        try {
            windowManager?.addView(orb, params)
            orbPulse = ValueAnimator.ofFloat(.96f, 1.045f).apply {
                duration = 1600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { value -> orb?.scaleX = value.animatedValue as Float; orb?.scaleY = value.animatedValue as Float }
                start()
            }
        } catch (_: Exception) { stopSelf() }
    }

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(14, 14, 14, 14); elevation = 18f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(17, 24, 39)); cornerRadius = 28f; setStroke(2, Color.rgb(92, 75, 151))
            }
        }
        menu.addView(TextView(this).apply { text = "✦  SHRADDHA QUICK ACTIONS"; setTextColor(Color.rgb(199, 183, 255)); textSize = 13f; setPadding(6, 4, 6, 12) })
        menu.addView(action("Insert नमस्ते", "Focused text field updated") { ShraddhaAccessibilityService.fillFocusedField("नमस्ते") })
        menu.addView(action("Scroll down", "Scroll requested") { ShraddhaAccessibilityService.scrollForward() })
        menu.addView(action("Back", "Back requested") { ShraddhaAccessibilityService.goBack() })
        menu.addView(action("Close menu", "") { true }.apply { setOnClickListener { removePanel() } })
        panel = menu
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(78); y = 0
        }
        try { windowManager?.addView(menu, p) } catch (_: Exception) { panel = null }
    }

    private fun action(label: String, success: String, action: () -> Boolean) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(42, 54, 82)))
        setOnClickListener { val ok = action(); if (success.isNotEmpty()) Toast.makeText(this@ShraddhaOverlayService, if (ok) success else "App Control चालू करें और सही field चुनें।", Toast.LENGTH_SHORT).show() }
    }

    private fun removePanel() { try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) {}; panel = null }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onDestroy() { orbPulse?.cancel(); orbPulse = null; removePanel(); try { orb?.let { windowManager?.removeView(it) } } catch (_: Exception) {}; orb = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}


private class FloatingOrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = minOf(width, height) * .39f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * .07f
        paint.color = 0x6655DDF5
        canvas.drawCircle(cx, cy, radius * 1.18f, paint)
        paint.strokeWidth = radius * .035f
        paint.color = 0x887B61F2.toInt()
        canvas.drawCircle(cx, cy, radius * 1.32f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - radius * .3f, cy - radius * .34f, radius * 1.4f,
            intArrayOf(0xFFF5F0FF.toInt(), 0xFFB9A5FF.toInt(), 0xFF6557D8.toInt(), 0xFF121A3B.toInt()),
            floatArrayOf(0f, .32f, .72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(cx - radius * .3f, cy - radius * .32f, radius * .17f, paint)
        paint.color = 0x557CEBFF
        canvas.drawCircle(cx + radius * .3f, cy + radius * .28f, radius * .2f, paint)
    }
}

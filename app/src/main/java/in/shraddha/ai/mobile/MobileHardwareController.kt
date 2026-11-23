package `in`.shraddha.ai.mobile

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

/** Android-safe device controls. Protected radios/settings open the OS-owned settings UI. */
object MobileHardwareController {
    fun setMediaVolume(context: Context, percent: Int): String {
        require(percent in 0..100) { "Volume 0–100% के बीच होना चाहिए।" }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100f).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Phone media volume ${percent}% कर दिया।"
    }

    fun setBrightness(context: Context, percent: Int): String {
        require(percent in 0..100) { "Brightness 0–100% के बीच होना चाहिए।" }
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "Brightness बदलने के लिए Android की Modify system settings अनुमति दें, फिर command दोबारा दें।"
        }
        val value = (percent * 255 / 100).coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return "Phone brightness ${percent}% कर दी।"
    }

    fun setTorch(context: Context, enabled: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "Flashlight के लिए Camera permission आवश्यक है। पहले अनुमति दें।"
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "इस फोन में flashlight उपलब्ध नहीं है।"
        manager.setTorchMode(cameraId, enabled)
        return if (enabled) "Phone flashlight चालू है।" else "Phone flashlight बंद है।"
    }

    fun status(context: Context): Map<String, Any?> {
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val wifiState = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }.getOrNull()
        val bluetoothState = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()
        return linkedMapOf(
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "battery_percent" to if (level >= 0) level * 100 / scale.coerceAtLeast(1) else null,
            "battery_temperature_c" to if (temp >= 0) temp / 10f else null,
            "ram_total_bytes" to memory.totalMem,
            "ram_available_bytes" to memory.availMem,
            "storage_total_bytes" to stat.totalBytes,
            "storage_available_bytes" to stat.availableBytes,
            "media_volume_percent" to (volume * 100 / maxVolume),
            "wifi_enabled" to wifiState,
            "bluetooth_enabled" to bluetoothState,
            "torch_hardware_available" to runCatching {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.cameraIdList.any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            }.getOrDefault(false),
            "brightness_write_permission" to Settings.System.canWrite(context)
        )
    }

    fun settingsIntent(name: String): Intent {
        val action = when (name.lowercase(Locale.ROOT)) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.INTERNET_CONNECTIVITY" else Settings.ACTION_WIRELESS_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

package com.Lia.assistant.action

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.net.URLEncoder

object CommIntents {
    /** Places a call directly after the user has granted CALL_PHONE permission. */
    fun placeCall(context: Context, phoneNumber: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.length < 5) return "invalid_phone_number"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "call_permission_required"
        }
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            "call_started"
        } catch (_: SecurityException) {
            "call_permission_required"
        } catch (_: Exception) {
            "call_failed"
        }
    }

    /** Sends SMS directly after the user has granted SEND_SMS permission. */
    fun sendSms(context: Context, recipient: String, message: String): String {
        val cleanRecipient = recipient.replace(Regex("[^0-9+]"), "")
        if (cleanRecipient.length < 5 || message.isBlank()) return "invalid_sms"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "sms_permission_required"
        }
        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(cleanRecipient, null, parts, null, null)
            else sms.sendTextMessage(cleanRecipient, null, message, null, null)
            "sms_sent_to_carrier"
        } catch (_: SecurityException) {
            "sms_permission_required"
        } catch (_: Exception) {
            "sms_send_failed"
        }
    }

    fun openWhatsAppComposer(context: Context, recipient: String, message: String): String {
        return try {
            val digitsOnly = recipient.filter { it.isDigit() }
            if (digitsOnly.length < 5 || message.isBlank()) return "invalid_whatsapp_recipient"
            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val url = "https://wa.me/$digitsOnly?text=$encodedMessage"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pm = context.packageManager
            val waPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
            val installedWa = waPackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            } ?: return "whatsapp_not_installed"
            intent.setPackage(installedWa)
            context.startActivity(intent)
            "whatsapp_chat_opened"
        } catch (_: Exception) {
            "whatsapp_failed"
        }
    }
}

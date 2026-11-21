package com.Lia.assistant.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Opens user-reviewable communication composers; never sends or calls without a user tap. */
object CommIntents {

    fun placeCall(context: Context, phoneNumber: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.length < 5) return "invalid_phone_number"
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            "dialer_opened"
        } catch (_: Exception) {
            "failed_to_open_dialer"
        }
    }

    fun openSmsComposer(context: Context, recipient: String, message: String): String {
        return try {
            val cleanRecipient = recipient.replace(Regex("[^0-9+]"), "")
            if (cleanRecipient.length < 5) return "invalid_phone_number"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$cleanRecipient")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "composer_opened"
        } catch (_: Exception) {
            "sms_composer_unavailable"
        }
    }

    fun openWhatsAppComposer(context: Context, recipient: String, message: String): String {
        val digitsOnly = recipient.filter { it.isDigit() }
        if (digitsOnly.length < 5) return "invalid_phone_number"
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val url = "https://wa.me/$digitsOnly?text=$encodedMessage"
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "composer_opened"
            } catch (_: Exception) {
                // Try the next installed WhatsApp variant.
            }
        }
        return "whatsapp_not_installed"
    }
}

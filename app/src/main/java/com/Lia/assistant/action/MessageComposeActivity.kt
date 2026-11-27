package com.Lia.assistant.action

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.*
import com.Lia.assistant.data.NovaPreferences
import java.util.Locale

/** Compose surface: pick a contact or enter E.164, dictate the body, then open final review. */
class MessageComposeActivity : Activity() {
    private lateinit var recipient: EditText
    private lateinit var body: EditText
    private lateinit var channel: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(5, 13, 23))
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI • MESSAGE COMPOSER"
            textSize = 19f; setTextColor(Color.WHITE)
        })
        channel = Spinner(this).apply {
            adapter = ArrayAdapter(this@MessageComposeActivity, android.R.layout.simple_spinner_dropdown_item, listOf("WhatsApp", "SMS"))
        }
        root.addView(channel)
        recipient = EditText(this).apply {
            hint = "पूरा नंबर country code सहित, जैसे +91XXXXXXXXXX"
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
        }
        root.addView(recipient)
        root.addView(Button(this).apply {
            text = "Contacts से नंबर चुनें"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                startActivityForResult(pick, REQUEST_CONTACT)
            }
        })
        body = EditText(this).apply {
            hint = "संदेश लिखें या बोलें…"
            minLines = 4; maxLines = 8; setTextColor(Color.WHITE); setHintTextColor(0xFF7896A8.toInt())
        }
        root.addView(body)
        root.addView(Button(this).apply {
            text = "🎙 संदेश बोलें"
            setOnClickListener {
                val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, NovaPreferences.getString(this@MessageComposeActivity, NovaPreferences.Keys.LANGUAGE, "hi-IN"))
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "संदेश बोलिए")
                }
                runCatching { startActivityForResult(speech, REQUEST_SPEECH) }
                    .onFailure { Toast.makeText(this@MessageComposeActivity, "Voice input उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
            }
        })
        root.addView(Button(this).apply {
            text = "Review करके भेजें"
            setOnClickListener {
                val number = recipient.text.toString().trim()
                val message = body.text.toString()
                if (!MessageApprovalPolicy.isValidE164(number)) {
                    recipient.error = "Country code सहित valid number लिखें"
                    return@setOnClickListener
                }
                if (message.isBlank() || message.length > 4000) {
                    body.error = "संदेश 1 से 4000 characters का होना चाहिए"
                    return@setOnClickListener
                }
                startActivity(Intent(this@MessageComposeActivity, MessageReviewActivity::class.java).apply {
                    putExtra(MessageReviewActivity.EXTRA_CHANNEL, if (channel.selectedItemPosition == 0) "whatsapp" else "sms")
                    putExtra(MessageReviewActivity.EXTRA_RECIPIENT, number)
                    putExtra(MessageReviewActivity.EXTRA_BODY, message)
                })
            }
        })
        root.addView(Button(this).apply { text = "रद्द करें"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQUEST_SPEECH) {
            val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) body.setText(spoken)
        } else if (requestCode == REQUEST_CONTACT) {
            val uri = data.data ?: return
            val number = contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (!number.isNullOrBlank()) recipient.setText(normalizePhone(number))
        }
    }

    private fun normalizePhone(raw: String): String = raw.trim().replace(Regex("[\\s().-]"), "")

    companion object {
        private const val REQUEST_CONTACT = 7319
        private const val REQUEST_SPEECH = 7320
    }
}

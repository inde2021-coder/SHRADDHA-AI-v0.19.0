package com.Lia.assistant.action

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Non-exported final review screen. The exact recipient and full body are shown before sending. */
class MessageReviewActivity : Activity() {
    private var sending = false
    private lateinit var status: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT).orEmpty().trim()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (channel !in setOf("sms", "whatsapp") ||
            !MessageApprovalPolicy.isValidE164(recipient) ||
            body.isBlank() || body.length > 4000
        ) {
            finishWithError("Message request invalid है। कोई संदेश नहीं भेजा गया।")
            return
        }
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
            setBackgroundColor(Color.rgb(5, 13, 23))
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI • MESSAGE REVIEW"
            textSize = 19f; setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Channel: ${channel.uppercase()}\nRecipient: $recipient"
            textSize = 15f; setTextColor(0xFF8DE4E5.toInt())
            setPadding(0, 24, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = body; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(16, 18, 16, 18)
            setBackgroundColor(0xFF142536.toInt())
        })
        status = TextView(this).apply {
            text = "भेजने से पहले प्राप्तकर्ता और पूरा संदेश जाँचें।"
            textSize = 13f; setTextColor(0xFFBBD8E2.toInt())
            setPadding(0, 18, 0, 12)
        }
        root.addView(status)
        sendButton = Button(this).apply {
            text = "यही संदेश भेजें"
            setOnClickListener {
                if (sending) return@setOnClickListener
                AlertDialog.Builder(this@MessageReviewActivity)
                    .setTitle("संदेश भेजने की अंतिम पुष्टि")
                    .setMessage("$recipient\n\n$body")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("भेजें") { _, _ ->
                        val approval = runCatching {
                            MessageSendApproval.afterExplicitConfirmation(channel, recipient, body, requestId)
                        }.getOrElse { status.text = it.message ?: "Approval failed"; return@setPositiveButton }
                        sendButton.isEnabled = false
                        sending = true
                        status.text = "Provider को संदेश भेजा जा रहा है…"
                        Thread {
                            val result = runCatching {
                                runBlocking {
                                    ProviderMessageClient.send(
                                        applicationContext, channel, recipient, body, requestId, approval
                                    ).getOrThrow()
                                }
                            }
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                result.onSuccess { receipt ->
                                    status.text = "Provider receipt: ${receipt.status.ifBlank { "accepted" }} • ID: ${receipt.messageId.take(32)}"
                                    sendButton.isEnabled = false
                                }.onFailure { error ->
                                    status.text = "संदेश भेजना पुष्ट नहीं हुआ: ${error.localizedMessage ?: "network/provider error"}"
                                    sendButton.isEnabled = true
                                    sending = false
                                }
                            }
                        }.start()
                    }.show()
            }
        }
        root.addView(sendButton)
        root.addView(Button(this).apply {
            text = "रद्द करें"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun finishWithError(message: String) {
        setContentView(TextView(this).apply {
            text = message; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(5, 13, 23)); textSize = 16f
        })
    }

    companion object {
        const val EXTRA_CHANNEL = "messageChannel"
        const val EXTRA_RECIPIENT = "messageRecipientE164"
        const val EXTRA_BODY = "messageExactBody"
    }
}

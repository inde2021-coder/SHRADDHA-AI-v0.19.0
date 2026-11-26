package in.shraddha.ai.future

import android.content.Context

/**
 * Single source of truth for user consent before any cloud AI request.
 * The default is OFF so upgrades and fresh installs never silently enable uploads.
 */
object OnlineAiConsent {
    private const val PREFS = "shraddha_network_ai"
    private const val KEY_ALLOW_ONLINE_AI = "allow_online_ai"

    fun isAllowed(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_ONLINE_AI, false)

    fun requireAllowed(context: Context) {
        check(isAllowed(context)) {
            "Online AI is disabled. Enable Allow Online AI in Settings > Connections before using cloud services."
        }
    }
}

package `in`.shraddha.ai.future

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class LocalAuditTrailInstrumentedTest {
    @Test fun auditIsEncryptedAtRestAndLegacyPlaintextIsPurged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("shraddha_audit_trail", Context.MODE_PRIVATE)
        val feature = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
        val trail = LocalAuditTrail(context)
        try {
            legacy.edit().putString("events", "[{\"detail\":\"legacy secret\"}]").commit()
            assertTrue(trail.append("operator_action", "verified", "action=CLICK_TEXT; target=Save").isSuccess)
            assertEquals(null, legacy.getString("events", null))
            val raw = feature.getString("security_audit_v2", null)
            assertNotNull(raw)
            assertFalse(raw!!.contains("operator_action"))
            assertFalse(raw.contains("target=Save"))
            val exported = trail.exportText().getOrThrow()
            assertTrue(exported.contains("operator_action"))
            assertTrue(exported.contains("verified"))
        } finally {
            trail.clear()
            legacy.edit().remove("events").commit()
        }
    }

    @Test fun auditClearRemovesEncryptedAndLegacyRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trail = LocalAuditTrail(context)
        val feature = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
        try {
            assertTrue(trail.append("task", "started").isSuccess)
            assertTrue(trail.clear())
            assertEquals(null, feature.getString("security_audit_v2", null))
        } finally {
            trail.clear()
        }
    }
}

package com.example.consent_cam.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
    @Test
    fun identifiesOnlyExactSupportedModelSizes() {
        val e2b = LocalModelCatalog.preferredModels.last()
        assertEquals(e2b, LocalModelCatalog.identifyBySize(e2b.expectedBytes))
        assertEquals(null, LocalModelCatalog.identifyBySize(e2b.expectedBytes - 1))
    }

    @Test
    fun auditPromptContainsOnlyStructuredNonIdentityFacts() {
        val prompt = PrivacyAuditSnapshot(
            trackedFaces = 2,
            nearbyConsentSessions = 3,
            nearbyProtectSessions = 1,
            protectedRegions = 1,
            privacyMode = "PRECISE",
        ).prompt()

        assertTrue(prompt.contains("tracked_faces=2"))
        assertTrue(prompt.contains("nearby_protect_sessions=1"))
        assertTrue(prompt.contains("Do not infer identity"))
        assertFalse(prompt.contains("embedding", ignoreCase = true))
    }

    @Test
    fun modelPreferenceIsE4bThenE2bAndVersionsArePinned() {
        val models = LocalModelCatalog.preferredModels

        assertTrue(models[0].displayName.contains("E4B"))
        assertTrue(models[1].displayName.contains("E2B"))
        assertTrue(models.all { it.expectedBytes > 0L && it.sourceRevision.length == 40 })
    }
}

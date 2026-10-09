package com.example.consent_cam.recognition

import com.example.consent_cam.ble.AppearanceConsent
import org.junit.Assert.assertEquals
import org.junit.Test

class ExactFaceMatcherDecisionTest {
    @Test
    fun decisionCoversProtectAllowUnknownAndAmbiguous() {
        val protect = profile(1u, AppearanceConsent.PROTECT)
        val allow = profile(2u, AppearanceConsent.ALLOW)
        try {
            assertEquals(
                FaceMatchState.MATCHED_PROTECT,
                ExactFaceMatcher.decide(7, listOf(protect to 0.91f)).state,
            )
            assertEquals(
                FaceMatchState.MATCHED_ALLOW,
                ExactFaceMatcher.decide(7, listOf(allow to 0.91f)).state,
            )
            assertEquals(
                FaceMatchState.UNKNOWN,
                ExactFaceMatcher.decide(7, listOf(protect to 0.63f)).state,
            )
            assertEquals(
                FaceMatchState.MATCHED_PROTECT,
                ExactFaceMatcher.decide(7, listOf(protect to 0.64f)).state,
            )
            assertEquals(
                FaceMatchState.AMBIGUOUS,
                ExactFaceMatcher.decide(7, listOf(protect to 0.91f, allow to 0.86f)).state,
            )
            val protect2 = profile(3u, AppearanceConsent.PROTECT)
            try {
                assertEquals(
                    FaceMatchState.MATCHED_PROTECT,
                    ExactFaceMatcher.decide(7, listOf(protect to 0.91f, protect2 to 0.86f)).state,
                )
            } finally {
                protect2.close()
            }
        } finally {
            protect.close()
            allow.close()
        }
    }

    @Test
    fun noCompatibleProfileIsUnknown() {
        assertEquals(FaceMatchState.UNKNOWN, ExactFaceMatcher.decide(3, emptyList()).state)
    }

    private fun profile(sessionId: UInt, consent: AppearanceConsent): SessionProfile = SessionProfile(
        sessionId = sessionId,
        consent = consent,
        modelId = FACENET_MODEL_ID,
        embedding = FloatArray(FACENET_EMBEDDING_DIMENSIONS).also { it[0] = 1f },
    )
}

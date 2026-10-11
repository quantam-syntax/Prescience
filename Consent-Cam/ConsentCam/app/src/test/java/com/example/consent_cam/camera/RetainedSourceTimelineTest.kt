package com.example.consent_cam.camera

import com.atreides.voiceconsent.LocalSpeechDecision
import com.atreides.voiceconsent.PcmConsentRedactor
import com.atreides.voiceconsent.SpeechState
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RetainedSourceTimelineTest {
    @Test
    fun openingSpeechUsesRetainedStreamEvenBeforeFirstProtectedDecision() {
        val retained = floatArrayOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val protected = listOf(LocalSpeechDecision(3, 6, 0.8f, SpeechState.PROTECTED))
        val friend = listOf(LocalSpeechDecision(0, 6, 0f, SpeechState.UNMATCHED))

        assertArrayEquals(retained, PcmConsentRedactor.redactRetainedSource(retained, protected, friend), 0f)
    }

    @Test
    fun protectedOnlySpanIsMutedWithoutMutingFriendOverlap() {
        val retained = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val protected = listOf(
            LocalSpeechDecision(0, 2, 0.8f, SpeechState.PROTECTED),
            LocalSpeechDecision(3, 6, 0.8f, SpeechState.PROTECTED),
        )
        val friend = listOf(LocalSpeechDecision(3, 6, 0f, SpeechState.UNMATCHED))

        assertArrayEquals(
            floatArrayOf(0f, 0f, 0.3f, 0.4f, 0.5f, 0.6f),
            PcmConsentRedactor.redactRetainedSource(retained, protected, friend),
            0f,
        )
    }

    @Test
    fun oneFriendWordDoesNotRestoreEntireProtectedTurn() {
        val retained = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val protected = listOf(LocalSpeechDecision(0, 6, 0.8f, SpeechState.PROTECTED))
        val friend = listOf(LocalSpeechDecision(3, 5, 0f, SpeechState.UNMATCHED))

        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 0.4f, 0.5f, 0f),
            PcmConsentRedactor.redactRetainedSource(retained, protected, friend),
            0f,
        )
    }
}

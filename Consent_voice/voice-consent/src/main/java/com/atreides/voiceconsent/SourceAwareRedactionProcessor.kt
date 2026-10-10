package com.atreides.voiceconsent

/** Result counters are safe to surface in the export UI; no PCM is retained. */
data class SourceAwareRedactionResult(
    val sanitized: FloatArray,
    val protectedOnlyMuted: Int,
    val overlapSeparated: Int,
    val overlapFullyMutedForSafety: Int,
    val backend: SeparatorBackend,
    val warning: String? = null,
)

/**
 * Controls how an ambiguous two-source result is handled.
 *
 * [PRIVACY_FIRST] is the default: if residual protected speech makes both
 * separated streams match, the overlap is muted. [HIGHEST_PASSPORT_SCORE_FOR_DEMO]
 * picks the stream with the stronger Passport match and keeps the other stream.
 * The latter can leave faint protected-voice residue when separation is poor, so
 * it must be presented as a review-before-sharing mode rather than a guarantee.
 */
enum class SourceSelectionPolicy {
    PRIVACY_FIRST,
    HIGHEST_PASSPORT_SCORE_FOR_DEMO,
}

/**
 * Uses separation only to replace a verified overlap. All unsuccessful paths
 * retain the established conservative mute behavior.
 */
class SourceAwareRedactionProcessor(
    private val separator: TwoSpeakerSeparator,
    private val selectionPolicy: SourceSelectionPolicy = SourceSelectionPolicy.PRIVACY_FIRST,
) {
    fun redact(
        mixed: FloatArray,
        pipeline: OnDeviceVoicePipeline,
        passport: List<FloatArray>,
    ): SourceAwareRedactionResult {
        val mixedDecisions = pipeline.decisions(mixed, passport)
        val protectedOnly = mixedDecisions.count { it.state == SpeechState.PROTECTED }
        val separated = separator.separate(mixed)
        if (separated !is SeparatorResult.Success ||
            separated.sourceA16k.size != mixed.size || separated.sourceB16k.size != mixed.size
        ) {
            return SourceAwareRedactionResult(
                sanitized = PcmConsentRedactor.redact(mixed, mixedDecisions),
                protectedOnlyMuted = protectedOnly,
                overlapSeparated = 0,
                overlapFullyMutedForSafety = mixedDecisions.count { it.state == SpeechState.OVERLAP },
                // A loaded bridge is not evidence of an HTP inference. Report
                // acceleration only from a successful native result.
                backend = SeparatorBackend.UNAVAILABLE,
                warning = (separated as? SeparatorResult.Unavailable)?.reason ?: (separated as? SeparatorResult.Failed)?.reason,
            )
        }

        val a = pipeline.decisions(separated.sourceA16k, passport)
        val b = pipeline.decisions(separated.sourceB16k, passport)
        val aProtected = a.filter { it.state == SpeechState.PROTECTED }
        val bProtected = b.filter { it.state == SpeechState.PROTECTED }
        val aScore = highestScore(a)
        val bScore = highestScore(b)
        // The normal policy requires a single confidently identified source.
        // Demo mode resolves residual matching in both streams by selecting the
        // stronger Passport score, then redacts that whole source from overlaps.
        val selected = when {
            aProtected.isNotEmpty() && bProtected.isEmpty() -> SelectedSource(aProtected, b, separated.sourceB16k, null)
            bProtected.isNotEmpty() && aProtected.isEmpty() -> SelectedSource(bProtected, a, separated.sourceA16k, null)
            selectionPolicy == SourceSelectionPolicy.HIGHEST_PASSPORT_SCORE_FOR_DEMO &&
                aProtected.isNotEmpty() && bProtected.isNotEmpty() && aScore.isFinite() && bScore.isFinite() -> {
                if (aScore >= bScore) {
                    SelectedSource(aProtected, b, separated.sourceB16k, "A", aScore, bScore)
                } else {
                    SelectedSource(bProtected, a, separated.sourceA16k, "B", bScore, aScore)
                }
            }
            else -> SelectedSource(emptyList(), emptyList(), FloatArray(0), null)
        }
        if (selected.protected.isEmpty()) {
            return SourceAwareRedactionResult(
                sanitized = PcmConsentRedactor.redact(mixed, mixedDecisions),
                protectedOnlyMuted = protectedOnly,
                overlapSeparated = 0,
                overlapFullyMutedForSafety = mixedDecisions.count { it.state == SpeechState.OVERLAP },
                backend = separated.backend,
                warning = "Separated sources did not identify a protected source suitable for replacement",
            )
        }
        val replacements = selected.protected.mapNotNull { decision ->
            // Do not replace protected-only speech. In demo mode a residual
            // protected match on the retained source is expected, so any speech
            // decision on that source is enough to prove an overlap candidate.
            val otherSpeech = selected.other.firstOrNull { intersects(it, decision) } ?: return@mapNotNull null
            val start = decision.startSample.coerceIn(0, mixed.size)
            val end = decision.endSample.coerceIn(start, mixed.size)
            selected.retainedAudio.copyOfRange(start, end).takeIf { it.isNotEmpty() }?.let {
                LocalReplacementAudio(start, end, it)
            }
        }
        val decisions = mixedDecisions + selected.protected.map {
            LocalSpeechDecision(it.startSample, it.endSample, it.score, SpeechState.OVERLAP)
        }
        return SourceAwareRedactionResult(
            sanitized = PcmConsentRedactor.redactWithReplacements(mixed, decisions, replacements),
            protectedOnlyMuted = protectedOnly,
            overlapSeparated = replacements.size,
            overlapFullyMutedForSafety = selected.protected.size - replacements.size,
            backend = separated.backend,
            warning = selected.demoWinner?.let { winner ->
                "Review before sharing: demo mode selected stream $winner by the stronger Passport score " +
                    "(${formatScore(selected.winnerScore)} vs ${formatScore(selected.otherScore)})."
            },
        )
    }

    private data class SelectedSource(
        val protected: List<LocalSpeechDecision>,
        val other: List<LocalSpeechDecision>,
        val retainedAudio: FloatArray,
        val demoWinner: String?,
        val winnerScore: Float = Float.NaN,
        val otherScore: Float = Float.NaN,
    )

    private fun highestScore(decisions: List<LocalSpeechDecision>): Float =
        decisions.map { it.score }.filter { it.isFinite() }.maxOrNull() ?: Float.NaN

    private fun formatScore(score: Float): String = "%.2f".format(java.util.Locale.US, score)

    private fun intersects(left: LocalSpeechDecision, right: LocalSpeechDecision): Boolean =
        left.startSample < right.endSample && right.startSample < left.endSample
}

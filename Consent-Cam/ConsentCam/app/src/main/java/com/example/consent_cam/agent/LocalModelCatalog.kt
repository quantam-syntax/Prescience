package com.example.consent_cam.agent

import java.io.File

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val expectedBytes: Long,
    val sourceRevision: String,
)

object LocalModelCatalog {
    val preferredModels = listOf(
        LocalModelSpec(
            id = "google/gemma-3n-E4B-it-litert-lm",
            displayName = "Gemma 3n E4B int4",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            expectedBytes = 4_919_541_760L,
            sourceRevision = "297ed75955702dec3503e00c2c2ecbbf475300bc",
        ),
        LocalModelSpec(
            id = "google/gemma-3n-E2B-it-litert-lm",
            displayName = "Gemma 3n E2B int4",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            expectedBytes = 3_655_827_456L,
            sourceRevision = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
        ),
    )

    fun findAvailable(directories: List<File>): List<Pair<LocalModelSpec, File>> =
        preferredModels.mapNotNull { spec ->
            directories.asSequence()
                .map { directory -> File(directory, spec.fileName) }
                .firstOrNull { file -> file.isFile && file.length() == spec.expectedBytes }
                ?.let { file -> spec to file }
        }

    fun identifyBySize(bytes: Long): LocalModelSpec? = preferredModels.singleOrNull {
        it.expectedBytes == bytes
    }
}

data class PrivacyAuditSnapshot(
    val trackedFaces: Int,
    val nearbyConsentSessions: Int,
    val nearbyProtectSessions: Int,
    val protectedRegions: Int,
    val privacyMode: String,
) {
    init {
        require(trackedFaces >= 0)
        require(nearbyConsentSessions >= 0)
        require(nearbyProtectSessions in 0..nearbyConsentSessions)
        require(protectedRegions >= 0)
        require(privacyMode.isNotBlank())
    }

    fun prompt(): String = """
        Explain this non-identifying ConsentCam privacy snapshot in two short sentences.
        mode=$privacyMode
        tracked_faces=$trackedFaces
        nearby_consent_sessions=$nearbyConsentSessions
        nearby_protect_sessions=$nearbyProtectSessions
        protected_regions=$protectedRegions
        Do not infer identity. Do not recommend unblurring. State that processing is on-device.
    """.trimIndent()

    fun questionPrompt(question: String): String = """
        You are answering a question about the ConsentCam app using only the supplied snapshot.
        Do not use outside knowledge. Do not identify a person, invent a cause, change privacy,
        or recommend unblurring. If the snapshot cannot answer the question, say exactly:
        "That information is not available in ConsentCam's current state."
        Answer in at most three short sentences and mention on-device processing when relevant.
        question=${question.take(400)}
        mode=$privacyMode
        tracked_faces=$trackedFaces
        nearby_consent_sessions=$nearbyConsentSessions
        nearby_protect_sessions=$nearbyProtectSessions
        protected_regions=$protectedRegions
    """.trimIndent()
}

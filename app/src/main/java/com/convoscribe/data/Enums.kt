package com.convoscribe.data

/**
 * Lifecycle of a [SessionEntity], from "we just hit record" to
 * "the speakers are labelled". A failed diarization lands on
 * [DIARIZATION_FAILED] so the UI can still show the transcript and
 * a Retry button without blocking the rest of the session.
 */
enum class SessionStatus {
    RECORDING,
    TRANSCRIBED,
    DIARIZING,
    DIARIZED,
    DIARIZATION_FAILED
}

/**
 * What kind of [ChatMessageEntity] this is. Summary messages are the
 * output of the LLM "summarise" action; Q&A messages are user
 * questions paired with assistant answers.
 */
enum class MessageKind {
    SUMMARY,
    QA
}

/**
 * Who produced a [ChatMessageEntity] entry. A Q&A round produces two
 * rows with the same [MessageKind] but different [MessageRole]s.
 */
enum class MessageRole {
    USER,
    ASSISTANT
}

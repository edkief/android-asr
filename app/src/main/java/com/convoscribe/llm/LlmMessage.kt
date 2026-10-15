package com.convoscribe.llm

/**
 * A single message in an OpenAI-style chat conversation. The roles
 * are the three OpenAI defines for `/v1/chat/completions`:
 *
 *  - `system` for top-level instructions (e.g. "you are a careful
 *    meeting transcriber");
 *  - `user` for the human turn;
 *  - `assistant` for the model's previous turn (used when we keep
 *    history across a multi-round "ask the LLM" conversation).
 *
 * The role string is stored verbatim because the wire format
 * already spells it out; any unknown value is forwarded as-is so a
 * future OpenAI role ("developer", "tool") keeps working without
 * us having to expand a Kotlin enum.
 */
data class LlmMessage(
    val role: String,
    val content: String
) {
    companion object {
        const val ROLE_SYSTEM: String = "system"
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"
    }
}

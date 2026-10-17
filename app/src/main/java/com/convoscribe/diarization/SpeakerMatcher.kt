package com.convoscribe.diarization

/**
 * Match diarized speaker embeddings against the user's
 * enrolled speakers using cosine similarity, so a fresh
 * recording can show real names ("Alice", "Bob") instead of
 * the generic "Speaker 1 / 2 / 3" labels.
 *
 * Pure Kotlin — no I/O, no Room, no native calls — so the
 * assignment algorithm is unit-tested without Robolectric.
 */
object SpeakerMatcher {

    /**
     * A single enrolled speaker: the name the user typed and the
     * reference embedding they enrolled with. The byte → float
     * conversion happens once at load time (see
     * [com.convoscribe.data.EmbeddingCodec]) so this class never
     * sees raw bytes.
     */
    data class Enrolled(
        val name: String,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Enrolled) return false
            if (name != other.name) return false
            if (!embedding.contentEquals(other.embedding)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + embedding.contentHashCode()
            return result
        }
    }

    /**
     * Cosine similarity between two equally-sized vectors,
     * clamped to `[-1, 1]` to absorb floating-point drift.
     *
     * Returns `0f` when either vector has zero norm — there's
     * no meaningful direction to compare against, so we don't
     * pretend there is one (a divide-by-zero would yield `NaN`,
     * which would silently break the threshold check below).
     * Returns `0f` for vectors of different sizes as well:
     * mismatched dimensions are a programmer error and the
     * caller almost certainly wants a hard zero (which the
     * threshold filter will drop) rather than a thrown
     * exception that aborts diarization.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        if (denom == 0.0) return 0f
        val raw = dot / denom
        // Numerical safety: clamp to the legitimate cosine
        // range. `1.0000001` happens when both vectors are
        // identical and we round up on the last multiply.
        return raw.coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * Greedy assignment of enrollment names to diarized speaker
     * indices, using cosine similarity between each speaker's
     * embedding and each enrolled speaker's reference vector.
     *
     * [embeddings] maps a 0-based speaker index (the diarizer's
     * raw id, already renumbered to user-facing `0..N` by
     * [SpeakerAssigner]) to its embedding. [enrolled] is the
     * list of references loaded from the database. [threshold]
     * is the minimum cosine similarity required for a match —
     * pairs below it are dropped, so a high threshold means
     * only confident matches get a name.
     *
     * Algorithm:
     *  1. Compute every `(speakerIndex, enrolledIndex)` cosine
     *     score. Keep only the ones `≥ threshold`.
     *  2. Sort the survivors by score, descending.
     *  3. Walk the sorted list, claiming the highest score
     *     first. Each speaker index can be claimed once and
     *     each enrolled *name* can be claimed once — the second
     *     claim on either side loses. A person with several
     *     voice samples has one row per sample; the walk reaches
     *     their best-matching sample first, so the person's
     *     score is effectively the max over their samples.
     *
     * The greedy step matches the "best score first, no
     * replanning" behaviour the spec calls out: a clear winner
     * is not sacrificed to an edge case, and ties on score
     * resolve deterministically by input order.
     */
    fun assignNames(embeddings: Map<Int, FloatArray>, enrolled: List<Enrolled>, threshold: Float): Map<Int, String> {
        if (embeddings.isEmpty() || enrolled.isEmpty()) return emptyMap()

        data class Candidate(val speaker: Int, val enrolledIndex: Int, val score: Float)

        // (1) Build the candidate list. We pair every speaker
        // with every enrolled name; the threshold + the greedy
        // walk below turn it into a one-to-one matching.
        val candidates = ArrayList<Candidate>(embeddings.size * enrolled.size)
        for ((speaker, embedding) in embeddings) {
            for ((enrolledIndex, ref) in enrolled.withIndex()) {
                val score = cosine(embedding, ref.embedding)
                if (score >= threshold) {
                    candidates.add(Candidate(speaker, enrolledIndex, score))
                }
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        // (2) Sort by score desc. Stable secondary order on
        // (speaker, enrolledIndex) keeps two equal scores
        // deterministic without pulling in a custom comparator.
        candidates.sortWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.speaker }
                .thenBy { it.enrolledIndex }
        )

        // (3) Greedy claim: each speaker / each enrolled name
        // used at most once. The result key is the speaker
        // index; the value is the user-typed name.
        val usedSpeakers = HashSet<Int>()
        // By name, not row: a person enrolled with several samples
        // has several rows, and must still label only one speaker.
        val usedNames = HashSet<String>()
        val result = LinkedHashMap<Int, String>()
        for (candidate in candidates) {
            if (candidate.speaker in usedSpeakers) continue
            val name = enrolled[candidate.enrolledIndex].name
            if (name in usedNames) continue
            usedSpeakers.add(candidate.speaker)
            usedNames.add(name)
            result[candidate.speaker] = enrolled[candidate.enrolledIndex].name
        }
        return result
    }
}

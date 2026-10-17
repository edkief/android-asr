package com.convoscribe.data

import com.convoscribe.diarization.SpeakerMatcher
import kotlinx.coroutines.flow.Flow

/**
 * The interface the rest of the app depends on for v2 enrolled
 * speakers. Keeps Room out of the rest of the codebase so a
 * future task can swap the database without rewriting every
 * caller. Methods that return embeddings
 * ([SpeakerMatcher.Enrolled]) hand them back as `FloatArray`
 * (the decoded form), not raw bytes, so callers do not need to
 * know about [EmbeddingCodec].
 */
interface EnrolledSpeakerStore {

    /**
     * Every enrollment, sorted by name. Used by the UI to render
     * the enrollment list and react to inserts / deletes.
     */
    fun observeAll(): Flow<List<EnrolledSpeakerEntity>>

    /**
     * Snapshot read of the current enrollments with their
     * embeddings already decoded into `FloatArray`s. Called by
     * the diarization pipeline once per session; a Flow would
     * re-trigger the whole cosine matching for every row
     * change, which the pipeline does not want.
     */
    suspend fun getAll(): List<SpeakerMatcher.Enrolled>

    /**
     * Add [embedding] as a voice sample for [name]. A person keeps
     * several samples (each enrollment, or "Remember this voice" from
     * a session, adds one); matching uses the best of them. Only the
     * newest [MAX_SAMPLES_PER_PERSON] are kept.
     */
    suspend fun save(name: String, embedding: FloatArray)

    @Suppress("unused")
    suspend fun delete(id: Long)
}

/**
 * Room-backed implementation of [EnrolledSpeakerStore]. The DAO
 * is held as a constructor parameter (rather than fetched off
 * the [AppDatabase]) so a future test can pass an in-memory DAO
 * without building the rest of the database.
 */
class EnrolledSpeakerRepository(
    private val dao: EnrolledSpeakerDao
) : EnrolledSpeakerStore {

    override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = dao.observeAll()

    override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = dao.getAll().map { row ->
        SpeakerMatcher.Enrolled(
            name = row.name,
            embedding = row.embedding.toFloats()
        )
    }

    override suspend fun save(name: String, embedding: FloatArray) {
        dao.insert(
            EnrolledSpeakerEntity(
                name = name,
                embedding = embedding.toBytes(),
                createdAt = System.currentTimeMillis()
            )
        )
        dao.pruneName(name, MAX_SAMPLES_PER_PERSON)
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }
}

/**
 * Voice samples kept per enrolled person. Several samples recorded in
 * different rooms and on different days cover more of how someone
 * sounds; old ones beyond this are dropped.
 */
const val MAX_SAMPLES_PER_PERSON: Int = 5

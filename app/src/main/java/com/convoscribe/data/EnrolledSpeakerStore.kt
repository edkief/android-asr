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
     * Insert or replace the enrollment named [name] with the
     * given [embedding]. Saving the same name twice keeps a
     * single row with the newer embedding — see
     * [EnrolledSpeakerDao.upsertByName].
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
        dao.upsertByName(
            EnrolledSpeakerEntity(
                name = name,
                embedding = embedding.toBytes(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }
}

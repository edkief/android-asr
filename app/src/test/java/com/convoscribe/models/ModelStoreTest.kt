package com.convoscribe.models

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): Pair<ModelStore, File> {
        val root = tmp.newFolder("models")
        return ModelStore(root) to root
    }

    /** Writes [file] with exactly [sizeBytes] on disk. */
    private fun writeSized(file: File, sizeBytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
    }

    @Test
    fun notInstalled_whenNoFiles() {
        val (store, _) = store()
        assertFalse(store.isInstalled(ModelCatalog.ASR_EN))
    }

    @Test
    fun notInstalled_whenFileMissing() {
        val (store, _) = store()
        // Create every EN file *except* tokens.txt.
        ModelCatalog.ASR_EN.files.forEach { file ->
            if (file.fileName != "tokens.txt") {
                writeSized(store.fileFor(ModelCatalog.ASR_EN, file.fileName), file.sizeBytes)
            }
        }
        assertFalse(store.isInstalled(ModelCatalog.ASR_EN))
    }

    @Test
    fun notInstalled_whenFileHasWrongSize() {
        val (store, _) = store()
        ModelCatalog.ASR_EN.files.forEach { file ->
            // One byte short — a typical truncated download.
            writeSized(
                store.fileFor(ModelCatalog.ASR_EN, file.fileName),
                file.sizeBytes - 1
            )
        }
        assertFalse(store.isInstalled(ModelCatalog.ASR_EN))
    }

    @Test
    fun installed_whenAllFilesExistWithExactSizes() {
        val (store, _) = store()
        ModelCatalog.ASR_EN.files.forEach { file ->
            writeSized(store.fileFor(ModelCatalog.ASR_EN, file.fileName), file.sizeBytes)
        }
        assertTrue(store.isInstalled(ModelCatalog.ASR_EN))
    }

    @Test
    fun installed_handlesSubDirectoryFileNames() {
        val (store, _) = store()
        // ENG bundles a `test_wavs/0.wav` whose name has a slash; the
        // store must create the parent directory on demand.
        ModelCatalog.ASR_EN.files.forEach { file ->
            writeSized(store.fileFor(ModelCatalog.ASR_EN, file.fileName), file.sizeBytes)
        }
        val nested = store.fileFor(ModelCatalog.ASR_EN, "test_wavs/0.wav")
        assertTrue(nested.exists())
        assertEquals(184_608L, nested.length())
    }

    @Test
    fun missing_returnsOnlyIncompleteBundles() {
        val (store, _) = store()
        // Install French completely, English not at all, segmentation partially.
        ModelCatalog.ASR_FR.files.forEach { file ->
            writeSized(store.fileFor(ModelCatalog.ASR_FR, file.fileName), file.sizeBytes)
        }
        // Segmentation has only one file, so to leave it partially
        // installed we write it at the wrong size.
        val segOnly = ModelCatalog.SEGMENTATION.files.first()
        writeSized(
            store.fileFor(ModelCatalog.SEGMENTATION, segOnly.fileName),
            1
        )

        val missing = store.missing(
            listOf(
                ModelCatalog.ASR_EN,
                ModelCatalog.ASR_FR,
                ModelCatalog.SEGMENTATION,
                ModelCatalog.EMBEDDING
            )
        )
        assertEquals(
            listOf(
                "asr-en-kroko-2025-08-06",
                "seg-pyannote-3-0-int8",
                "emb-wespeaker-resnet34-lm"
            ),
            missing.map { it.id }
        )
    }

    @Test
    fun delete_removesBundleDirectory() {
        val (store, root) = store()
        ModelCatalog.ASR_EN.files.forEach { file ->
            writeSized(store.fileFor(ModelCatalog.ASR_EN, file.fileName), file.sizeBytes)
        }
        val dir = store.bundleDir(ModelCatalog.ASR_EN)
        assertTrue(dir.exists())
        assertTrue(root.listFiles()!!.any { it.name == ModelCatalog.ASR_EN.id })

        store.delete(ModelCatalog.ASR_EN)

        assertFalse(dir.exists())
        // Root directory itself must remain.
        assertTrue(root.exists())
    }

    @Test
    fun delete_isNoOpForMissingBundle() {
        val (store, _) = store()
        // Should not throw.
        store.delete(ModelCatalog.EMBEDDING)
    }

    @Test
    fun bundleDir_isUnderRoot() {
        val (store, root) = store()
        val dir = store.bundleDir(ModelCatalog.SEGMENTATION)
        assertEquals(File(root, "seg-pyannote-3-0-int8"), dir)
    }
}

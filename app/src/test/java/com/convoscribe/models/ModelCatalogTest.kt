package com.convoscribe.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun requiredForEnglish_listsAsrSegAndEmbedding() {
        val ids = ModelCatalog.requiredFor("en").map { it.id }
        assertEquals(
            listOf(
                "asr-en-kroko-2025-08-06",
                "seg-pyannote-3-0-int8",
                "emb-wespeaker-resnet34-lm"
            ),
            ids
        )
    }

    @Test
    fun requiredForFrench_usesFrenchAsr() {
        val bundles = ModelCatalog.requiredFor("fr")
        assertEquals("asr-fr-kroko-2025-08-06", bundles[0].id)
        assertEquals("fr", bundles[0].language)
        // Same diarizer models as English.
        assertEquals("seg-pyannote-3-0-int8", bundles[1].id)
        assertEquals("emb-wespeaker-resnet34-lm", bundles[2].id)
    }

    @Test
    fun englishTotalBytes() {
        assertEquals(71_237_822L, ModelCatalog.ASR_EN.totalBytes)
    }

    @Test
    fun frenchTotalBytes() {
        assertEquals(71_052_319L, ModelCatalog.ASR_FR.totalBytes)
    }

    @Test
    fun everyUrlIsHttps() {
        ModelCatalog.all.flatMap { it.files }.forEach { file ->
            assertTrue(
                "URL ${file.url} should start with https://",
                file.url.startsWith("https://")
            )
        }
    }

    @Test
    fun asrForUnknownLanguage_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalog.asrFor("de")
        }
    }

    @Test
    fun asrFiles_arePopulatedForAsrBundles() {
        val asr = ModelCatalog.ASR_EN.asr
        requireNotNull(asr)
        assertEquals("encoder.onnx", asr.encoder)
        assertEquals("decoder.onnx", asr.decoder)
        assertEquals("joiner.onnx", asr.joiner)
        assertEquals("tokens.txt", asr.tokens)
        assertEquals("zipformer2", asr.modelType)
    }

    @Test
    fun nonAsrBundles_haveNoAsrFiles() {
        assertEquals(null, ModelCatalog.SEGMENTATION.asr)
        assertEquals(null, ModelCatalog.EMBEDDING.asr)
    }

    @Test
    fun all_isConsistent() {
        // The downloader iterates `all`; make sure every kind is covered.
        val kinds = ModelCatalog.all.map { it.kind }.toSet()
        assertEquals(
            setOf(
                ModelKind.ASR,
                ModelKind.SEGMENTATION,
                ModelKind.EMBEDDING
            ),
            kinds
        )
    }
}

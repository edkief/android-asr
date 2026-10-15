package com.convoscribe.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SettingsViewModel]. Robolectric only because
 * [SettingsRepository] needs a working [android.content.SharedPreferences];
 * the API key path uses [InMemorySecretStore] so we never touch the
 * Android Keystore. `ChatClient` is a fake that returns a
 * caller-supplied model list or throws a caller-supplied
 * [LlmException].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(
            "settings-vm-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        secrets = InMemorySecretStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRepo(): SettingsRepository = SettingsRepository(prefs, secrets)

    private fun newVm(
        repo: SettingsRepository = newRepo(),
        chatClient: ChatClient = FakeChatClient(models = emptyList())
    ): SettingsViewModel = SettingsViewModel(repo, chatClient)

    @Test
    fun save_persistsFormAndApiKeyAndReloadReadsThemBack() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        val form = SettingsForm(
            baseUrl = "http://192.168.1.10:8000/v1",
            apiKey = "sk-secret",
            model = "m1",
            maxChars = "12345",
            numThreads = 6,
            numSpeakers = 4,
            clusterThreshold = 0.4f
        )
        vm.update(form)
        vm.save()

        // A second repository sharing the same prefs file but a
        // brand-new SecretStore reads the plain fields but NOT
        // the API key — proving the key lives only in the
        // SecretStore and that save() did write it through the
        // existing SecretStore instance.
        val s = repo.settings.value
        assertEquals("http://192.168.1.10:8000/v1", s.llmBaseUrl)
        assertEquals("m1", s.llmModel)
        assertEquals(12345, s.maxTranscriptChars)
        assertEquals(6, s.numThreads)
        assertEquals(4, s.numSpeakers)
        assertEquals(0.4f, s.clusterThreshold, 0.0001f)
        assertEquals("sk-secret", repo.apiKey())

        val fresh = SettingsRepository(prefs, InMemorySecretStore())
        val s2 = fresh.settings.value
        assertEquals("http://192.168.1.10:8000/v1", s2.llmBaseUrl)
        assertEquals("m1", s2.llmModel)
        assertEquals(12345, s2.maxTranscriptChars)
        assertEquals("", fresh.apiKey())
    }

    @Test
    fun save_persistsEveryFieldIncludingApiKey() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.update(
            SettingsForm(
                baseUrl = "http://example.test/v1",
                apiKey = "sk-roundtrip",
                model = "gpt-test",
                maxChars = "9999",
                numThreads = 7,
                numSpeakers = -1,
                clusterThreshold = 0.7f
            )
        )
        vm.save()

        val s = repo.settings.value
        assertEquals("http://example.test/v1", s.llmBaseUrl)
        assertEquals("gpt-test", s.llmModel)
        assertEquals(9999, s.maxTranscriptChars)
        assertEquals(7, s.numThreads)
        assertEquals(-1, s.numSpeakers)
        assertEquals(0.7f, s.clusterThreshold, 0.0001f)
        assertEquals("sk-roundtrip", repo.apiKey())

        assertTrue(vm.state.value.saved)
    }

    @Test
    fun save_invalidMaxCharsKeepsPreviousValue() {
        val repo = newRepo()
        // Pre-seed maxTranscriptChars to a known value.
        repo.update { it.copy(maxTranscriptChars = 8800) }
        val vm = newVm(repo = repo)
        vm.update(SettingsForm(maxChars = "")) // empty → toIntOrNull == null
        vm.save()
        assertEquals(8800, repo.settings.value.maxTranscriptChars)

        vm.update(SettingsForm(maxChars = "not a number"))
        vm.save()
        assertEquals(8800, repo.settings.value.maxTranscriptChars)
    }

    @Test
    fun testConnection_withFakeModelsListsPopulatesTestResultAndAvailableModels() = runTest(dispatcher) {
        val fake = FakeChatClient(models = listOf("m1", "m2"))
        val repo = newRepo()
        val vm = newVm(repo = repo, chatClient = fake)
        vm.update(SettingsForm(baseUrl = "http://example.test/v1", model = "m1"))
        vm.testConnection()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.testing)
        assertEquals("Connected – 2 models: m1, m2", s.testResult)
        assertEquals(listOf("m1", "m2"), s.availableModels)
    }

    @Test
    fun testConnection_withThrowingFakeSetsFailureMessage() = runTest(dispatcher) {
        val fake = FakeChatClient(error = LlmException("HTTP 401: x", httpCode = 401))
        val vm = newVm(repo = newRepo(), chatClient = fake)
        vm.testConnection()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.testing)
        assertNotNull(s.testResult)
        assertEquals("Failed: HTTP 401: x", s.testResult)
        assertTrue(s.availableModels.isEmpty())
    }

    @Test
    fun testConnection_stripsTrailingSlashWhenSavingBeforeTest() = runTest(dispatcher) {
        val fake = FakeChatClient(models = listOf("m"))
        val repo = newRepo()
        val vm = newVm(repo = repo, chatClient = fake)
        vm.update(SettingsForm(baseUrl = "http://example.test/v1/", model = "m"))
        vm.testConnection()
        advanceUntilIdle()
        // The trailing slash was stripped before chatClient was
        // called; repo is the canonical source.
        assertEquals("http://example.test/v1", repo.settings.value.llmBaseUrl)
        assertEquals("Connected – 1 models: m", vm.state.value.testResult)
    }

    @Test
    fun setEngine_persistsImmediatelyAndIgnoresUnknownValues() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.setEngine("cpu")
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertEquals("cpu", vm.state.value.asrEngine)
        // Unknown value must NOT touch the persistence layer or
        // the form state.
        vm.setEngine("hopper")
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertEquals("cpu", vm.state.value.asrEngine)
    }

    @Test
    fun update_replacesForm() {
        val vm = newVm()
        vm.update(SettingsForm(model = "first"))
        assertEquals("first", vm.state.value.form.model)
        vm.update(SettingsForm(model = "second"))
        assertEquals("second", vm.state.value.form.model)
    }

    @Test
    fun acknowledgeSaved_resetsSavedFlag() {
        val vm = newVm()
        vm.update(SettingsForm(model = "x"))
        vm.save()
        assertTrue(vm.state.value.saved)
        vm.acknowledgeSaved()
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun initialState_seedsFromRepository() = runTest(dispatcher) {
        val repo = newRepo()
        repo.update { it.copy(llmBaseUrl = "http://seed/v1", llmModel = "m-seed", numThreads = 3) }
        repo.setApiKey("sk-seed")
        // Build a fresh repository reading from the same prefs +
        // secrets so we exercise the init path explicitly.
        val newRepo = SettingsRepository(prefs, secrets)
        val vm = newVm(repo = newRepo)
        val state = vm.state.first { it.form.baseUrl.isNotEmpty() }
        assertEquals("http://seed/v1", state.form.baseUrl)
        assertEquals("m-seed", state.form.model)
        assertEquals(3, state.form.numThreads)
        assertEquals("sk-seed", state.form.apiKey)
    }

    /**
     * Trivial in-memory [ChatClient] for tests. Returns the
     * configured `models` list, or throws the configured `error`
     * if one was provided.
     */
    private class FakeChatClient(
        private val models: List<String> = emptyList(),
        private val error: Throwable? = null
    ) : ChatClient {
        override fun stream(messages: List<LlmMessage>): Flow<String> = flowOf()

        override suspend fun listModels(): List<String> {
            error?.let { throw it }
            return models
        }
    }
}

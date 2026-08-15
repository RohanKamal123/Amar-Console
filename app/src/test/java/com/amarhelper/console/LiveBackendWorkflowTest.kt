package com.amarhelper.console

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.data.net.ApiClientFactory
import com.amarhelper.console.data.remote.opencode.OpenCodeEventStream
import com.amarhelper.console.data.remote.openhands.OpenHandsRealtimeClient
import com.amarhelper.console.data.repository.DefaultAgentRepository
import com.amarhelper.console.data.repository.DefaultServiceHealthRepository
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.TaskSubmission
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole user workflow, driven through the real repositories against a real HTTP
 * server on the other end of a real socket — service probes, task submission, streamed
 * output, history, a backend outage and recovery, and deletion.
 *
 * Opt-in: it needs a server, so it is skipped unless MOCK_BACKEND_URL is set.
 *
 *     python3 tools/mock_backend.py --port 8099 &
 *     MOCK_BACKEND_URL=http://127.0.0.1:8099 ./gradlew testDebugUnitTest --tests '*LiveBackendWorkflowTest*'
 *
 * Point it at your own stack instead of the mock and it exercises that just as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveBackendWorkflowTest {

    private val base: String? = System.getenv("MOCK_BACKEND_URL")

    @Test
    fun full_workflow() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        assumeTrue("MOCK_BACKEND_URL not set; skipping live workflow", base != null)
        val base = base!!
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configStore = ConfigStore(context)
        configStore.clear()
        configStore.setUrl(ServiceId.OPEN_CODE, base)
        configStore.setUrl(ServiceId.OPEN_HANDS, base)
        configStore.setUrl(ServiceId.LITE_LLM, base)
        configStore.setUrl(ServiceId.GATEWAY, base)

        val credentials = SecureCredentialStore(context)
        val factory = ApiClientFactory(credentials, Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        val stream = OpenCodeEventStream(credentials, Json { ignoreUnknownKeys = true })
        val agents = DefaultAgentRepository(configStore, factory, stream, OpenHandsRealtimeClient(Json { ignoreUnknownKeys = true }, credentials))
        val healthRepo = DefaultServiceHealthRepository(configStore, factory)

        println("STEP 1 — service health")
        healthRepo.checkAll().forEach { println("   ${it.service.displayName}: ${it.state} ${it.latencyMillis}ms v=${it.version} ${it.detail ?: ""}") }
        check(healthRepo.checkAll().count { it.state == HealthState.ONLINE } == 4) { "expected all four services online" }

        println("STEP 2 — dependencies reported by gateway")
        healthRepo.dependencies().forEach { println("   ${it.name}: ${it.state}") }

        println("STEP 3 — submit a task to OpenCode")
        val submitted = agents.submitTask(
            TaskSubmission("Build a REST API for user authentication.", AgentProvider.OPEN_CODE),
        )
        val session = (submitted as ApiResult.Success).data
        println("   session=${session.id} state=${session.state} title=${session.title}")

        println("STEP 4 — collect streamed agent output")
        // Real-time work: runTest's virtual clock would time this out instantly.
        val events = withContext(Dispatchers.Default) {
            withTimeout(60_000) { stream.events(base, session.id).take(4).toList() }
        }
        events.forEach { println("   [${it.kind}] ${it.text}") }
        check(events.any { it.text.contains("auth", ignoreCase = true) }) { "no agent output referencing the task" }

        println("STEP 5 — transcript history")
        val history = (agents.history(AgentProvider.OPEN_CODE, session.id) as ApiResult.Success).data
        println("   ${history.size} lines replayed")
        check(history.isNotEmpty())

        println("STEP 6 — submit to OpenHands and list sessions from both providers")
        val oh = agents.submitTask(TaskSubmission("Add rate limiting to the auth API.", AgentProvider.OPEN_HANDS))
        val conversationId = (oh as ApiResult.Success).data.id
        println("   openhands session=$conversationId state=${oh.data.state}")

        println("STEP 6b — OpenHands transcript and stop")
        withContext(Dispatchers.Default) { Thread.sleep(4_000) }
        val transcript = agents.history(AgentProvider.OPEN_HANDS, conversationId)
        (transcript as ApiResult.Success).data.forEach { println("   [${it.kind}] ${it.text}") }
        check(transcript.data.isNotEmpty()) { "no OpenHands transcript" }
        val stopped = agents.cancel(AgentProvider.OPEN_HANDS, conversationId)
        println("   stop -> $stopped")
        check(stopped is ApiResult.Success) { "stop failed" }
        val afterStop = (agents.session(AgentProvider.OPEN_HANDS, conversationId) as ApiResult.Success).data
        println("   state after stop = ${afterStop.state}")
        val sessions = (agents.listSessions() as ApiResult.Success).data
        sessions.forEach { println("   ${it.provider.displayName} ${it.id} ${it.state} — ${it.title}") }
        check(sessions.size >= 2)

        println("STEP 7 — backend failure")
        java.net.URL("$base/__control/fail?status=500").readText()
        val failed = agents.listSessions()
        println("   listSessions -> $failed")
        check(failed is ApiResult.Failure)

        println("STEP 8 — recovery")
        java.net.URL("$base/__control/fail?status=0").readText()
        val recovered = agents.listSessions()
        check(recovered is ApiResult.Success) { "did not recover" }
        println("   recovered with ${(recovered as ApiResult.Success).data.size} sessions")

        println("STEP 9 — delete a session")
        println("   delete -> ${agents.deleteSession(AgentProvider.OPEN_CODE, session.id)}")

        configStore.clear()
        println("WORKFLOW OK")
    }
}

package com.amarhelper.console.ui

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.ConsoleEvent
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.fake.FakeAgentRepository
import com.amarhelper.console.ui.console.ConnectionState
import com.amarhelper.console.ui.console.SessionConsoleViewModel
import com.amarhelper.console.util.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import com.amarhelper.console.util.awaitCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionConsoleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configStore = ConfigStore(context)
    private val agents = FakeAgentRepository()

    private fun viewModel() = SessionConsoleViewModel(agents, configStore)

    @Test
    fun `history is replayed before live output arrives`() = runTest {
        agents.historyResult = ApiResult.Success(
            listOf(
                ConsoleEvent("1", ConsoleEvent.Kind.USER, "Build a REST API", 1L),
                ConsoleEvent("2", ConsoleEvent.Kind.AGENT, "Starting", 2L),
            ),
        )
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "ses_1")
        advanceUntilIdle()

        assertEquals(2, vm.state.value.events.size)
        assertEquals(ConnectionState.CONNECTING, vm.state.value.connection)
    }

    @Test
    fun `streamed events append and mark the console live`() = runTest {
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "ses_1")
        advanceUntilIdle()

        agents.liveEvents.emit(ConsoleEvent("e1", ConsoleEvent.Kind.AGENT, "Working on it", 10L))
        agents.liveEvents.emit(ConsoleEvent("e2", ConsoleEvent.Kind.TOOL, "bash()", 11L))
        advanceUntilIdle()

        assertEquals(ConnectionState.LIVE, vm.state.value.connection)
        assertEquals(listOf("Working on it", "bash()"), vm.state.value.events.map { it.text })
    }

    @Test
    fun `the event buffer is bounded and reports how much was trimmed`() = runTest {
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "ses_1")
        advanceUntilIdle()

        repeat(1_600) { index ->
            agents.liveEvents.emit(ConsoleEvent("e$index", ConsoleEvent.Kind.AGENT, "line $index", index.toLong()))
        }
        advanceUntilIdle()

        assertEquals(1_500, vm.state.value.events.size)
        assertEquals(100, vm.state.value.droppedLines)
        assertEquals("line 1599", vm.state.value.events.last().text)
    }

    @Test
    fun `a dropped stream is reported as disconnected and can be reconnected`() = runTest {
        agents.streamFailure = IOException("stream closed")
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "ses_1")
        advanceUntilIdle()

        assertEquals(ConnectionState.DISCONNECTED, vm.state.value.connection)
        assertTrue(vm.state.value.error is AppError.Offline)

        agents.streamFailure = null
        vm.reconnect()
        advanceUntilIdle()
        agents.liveEvents.emit(ConsoleEvent("e1", ConsoleEvent.Kind.AGENT, "back", 1L))
        advanceUntilIdle()

        assertEquals(ConnectionState.LIVE, vm.state.value.connection)
    }

    @Test
    fun `a provider without a stream is polled and polling stops once the task is terminal`() = runTest {
        agents.streaming = false
        agents.historyResult = ApiResult.Failure(AppError.Unsupported("Transcript replay for OpenHands"))
        agents.sessionResult = ApiResult.Success(FakeAgentRepository.session("ses_1", TaskState.RUNNING))
        configStore.setPollInterval(2)

        val vm = viewModel()
        vm.start(AgentProvider.OPEN_HANDS, "ses_1")
        // The poll loop always has another task queued, so advanceUntilIdle() would spin
        // forever; drain only what is due and wait for the real DataStore read to land.
        awaitCurrent { vm.state.value.connection == ConnectionState.POLLING }

        assertTrue(vm.state.value.notice!!.contains("isn't supported"))

        agents.sessionResult = ApiResult.Success(FakeAgentRepository.session("ses_1", TaskState.COMPLETED))
        advanceTimeBy(2_500)
        // Once the task is terminal the loop returns, leaving the scheduler idle.
        awaitCurrent { vm.state.value.connection == ConnectionState.NO_STREAM }

        assertEquals(TaskState.COMPLETED, vm.state.value.state)
    }

    @Test
    fun `a session that cannot be loaded surfaces the error instead of an empty console`() = runTest {
        agents.sessionResult = ApiResult.Failure(AppError.NotFound())
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "gone")
        advanceUntilIdle()

        assertTrue(vm.state.value.error is AppError.NotFound)
        assertEquals(ConnectionState.DISCONNECTED, vm.state.value.connection)
    }

    @Test
    fun `cancel reports honestly when the provider has no cancel endpoint`() = runTest {
        val vm = viewModel()
        vm.start(AgentProvider.OPEN_CODE, "ses_1")
        advanceUntilIdle()

        vm.cancelTask()
        advanceUntilIdle()

        assertTrue(vm.state.value.notice!!.contains("Cancelling a running"))
    }
}

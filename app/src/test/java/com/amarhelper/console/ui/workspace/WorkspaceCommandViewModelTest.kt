package com.amarhelper.console.ui.workspace

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.domain.model.RealtimeUpdate
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.fake.FakeAgentRepository
import com.amarhelper.console.util.MainDispatcherRule
import com.amarhelper.console.util.awaitUntil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCommandViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configStore = ConfigStore(context)
    private val agents = FakeAgentRepository()

    private fun viewModel() = WorkspaceCommandViewModel(agents, configStore)

    private suspend fun configure() {
        configStore.setUrl(ServiceId.OPEN_HANDS, "http://host.tail1234.ts.net:3000")
    }

    @After
    fun tearDown() = runTest { configStore.clear() }

    @Test
    fun `the conversation on screen is tracked from the address`() = runTest {
        val vm = viewModel()
        vm.onUrlChanged("http://host.tail1234.ts.net:3000/conversations/conv_7")
        advanceUntilIdle()

        assertEquals("conv_7", vm.state.value.conversationId)

        vm.onUrlChanged("http://host.tail1234.ts.net:3000/settings")
        advanceUntilIdle()

        assertEquals(null, vm.state.value.conversationId)
    }

    @Test
    fun `interrupt is offered only while the agent is working`() = runTest {
        val vm = viewModel()
        vm.onUrlChanged("http://host.tail1234.ts.net:3000/conversations/conv_7")
        advanceUntilIdle()
        assertFalse(vm.state.value.canInterrupt)

        agents.liveEvents.emit(RealtimeUpdate.AgentStatus("Agent: running", TaskState.RUNNING))
        advanceUntilIdle()
        assertTrue(vm.state.value.canInterrupt)

        agents.liveEvents.emit(RealtimeUpdate.AgentStatus("Agent: finished", TaskState.COMPLETED))
        advanceUntilIdle()
        assertFalse(vm.state.value.canInterrupt)
    }

    @Test
    fun `interrupt stops the conversation on screen`() = runTest {
        configure()
        agents.cancelResult = ApiResult.Success(Unit)
        val vm = viewModel()
        vm.onUrlChanged("http://host.tail1234.ts.net:3000/conversations/conv_7")
        advanceUntilIdle()

        vm.interrupt()
        // The command reads the base URL from DataStore on a real thread, which the
        // virtual clock does not wait for.
        awaitUntil { vm.state.value.notice != null }

        assertEquals("Interrupted.", vm.state.value.notice)
        assertFalse(vm.state.value.isWorking)
    }

    @Test
    fun `a failed interrupt reports why instead of pretending`() = runTest {
        configure()
        agents.cancelResult = ApiResult.Failure(AppError.Offline())
        val vm = viewModel()
        vm.onUrlChanged("http://host.tail1234.ts.net:3000/conversations/conv_7")
        advanceUntilIdle()

        vm.interrupt()
        awaitUntil { vm.state.value.notice != null }

        assertTrue(vm.state.value.notice!!.contains("Can't reach the backend"))
    }

    @Test
    fun `stop without an open conversation asks for one rather than calling the API`() = runTest {
        configure()
        val vm = viewModel()
        vm.onUrlChanged("http://host.tail1234.ts.net:3000/")
        vm.onInputChanged("/stop")
        vm.submit()
        awaitUntil { vm.state.value.notice != null }

        assertEquals("Open a conversation first.", vm.state.value.notice)
    }

    @Test
    fun `new navigates the page to the conversation it just created`() = runTest {
        configure()
        agents.submitResult = ApiResult.Success(FakeAgentRepository.session("conv_9"))
        val vm = viewModel()

        vm.onInputChanged("/new add rate limiting")
        vm.submit()
        advanceUntilIdle()

        val effect = vm.effect.first()
        assertEquals(
            WorkspaceEffect.Navigate("http://host.tail1234.ts.net:3000/conversations/conv_9"),
            effect,
        )
        assertEquals("add rate limiting", agents.submittedTasks.single().prompt)
    }

    @Test
    fun `text that is not a command is refused, not sent somewhere`() = runTest {
        configure()
        val vm = viewModel()
        vm.onInputChanged("please stop")
        vm.submit()
        awaitUntil { vm.state.value.notice != null }

        assertTrue(vm.state.value.notice!!.contains("Type a / command"))
        assertTrue(agents.submittedTasks.isEmpty())
    }

    @Test
    fun `an unconfigured OpenHands url is reported before any call is attempted`() = runTest {
        configStore.clear()
        val vm = viewModel()
        vm.onInputChanged("/sessions")
        vm.submit()
        awaitUntil { vm.state.value.notice != null }

        assertTrue(vm.state.value.notice!!.contains("Settings"))
    }

    @Test
    fun `picking a command that takes an argument leaves it ready to type`() = runTest {
        val vm = viewModel()
        vm.onSuggestionPicked(WorkspaceCommand.NEW)
        advanceUntilIdle()

        assertEquals("/new ", vm.state.value.input)
    }

    @Test
    fun `the input clears once a command runs`() = runTest {
        configure()
        val vm = viewModel()
        vm.onInputChanged("/reload")
        vm.submit()
        advanceUntilIdle()

        assertEquals("", vm.state.value.input)
        assertEquals(WorkspaceEffect.Reload, vm.effect.first())
    }
}

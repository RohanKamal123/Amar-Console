package com.amarhelper.console.ui

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.fake.FakeAgentRepository
import com.amarhelper.console.ui.sessions.SessionsViewModel
import com.amarhelper.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val agents = FakeAgentRepository()

    @Test
    fun `sessions are split into active and previous`() = runTest {
        agents.sessionsResult = ApiResult.Success(
            listOf(
                FakeAgentRepository.session("a", TaskState.RUNNING),
                FakeAgentRepository.session("b", TaskState.COMPLETED),
                FakeAgentRepository.session("c", TaskState.WAITING),
            ),
        )
        val vm = SessionsViewModel(agents)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.active.size)
        assertEquals(1, vm.state.value.previous.size)
    }

    @Test
    fun `a deleted session disappears from the list`() = runTest {
        agents.sessionsResult = ApiResult.Success(listOf(FakeAgentRepository.session("a")))
        val vm = SessionsViewModel(agents)
        advanceUntilIdle()

        vm.delete(AgentProvider.OPEN_CODE, "a")
        advanceUntilIdle()

        assertTrue(vm.state.value.sessions.isEmpty())
        assertEquals(listOf("a"), agents.deletedSessions)
    }

    @Test
    fun `deletion the provider does not support tells the user instead of pretending`() = runTest {
        agents.deleteResult = ApiResult.Failure(AppError.Unsupported("Deleting an OpenHands conversation"))
        agents.sessionsResult = ApiResult.Success(listOf(FakeAgentRepository.session("a", provider = AgentProvider.OPEN_HANDS)))
        val vm = SessionsViewModel(agents)
        advanceUntilIdle()

        vm.delete(AgentProvider.OPEN_HANDS, "a")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.sessions.size)
        assertTrue(vm.state.value.notice!!.contains("isn't supported"))
    }

    @Test
    fun `a listing failure is retryable`() = runTest {
        agents.sessionsResult = ApiResult.Failure(AppError.Offline())
        val vm = SessionsViewModel(agents)
        advanceUntilIdle()
        assertTrue(vm.state.value.error is AppError.Offline)

        agents.sessionsResult = ApiResult.Success(listOf(FakeAgentRepository.session("z")))
        vm.refresh()
        advanceUntilIdle()

        assertEquals("z", vm.state.value.sessions.first().id)
        assertEquals(null, vm.state.value.error)
    }
}

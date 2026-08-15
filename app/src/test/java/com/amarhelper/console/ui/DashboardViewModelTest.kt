package com.amarhelper.console.ui

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.domain.model.TaskState
import com.amarhelper.console.fake.FakeAgentRepository
import com.amarhelper.console.fake.FakeServiceHealthRepository
import com.amarhelper.console.ui.dashboard.DashboardViewModel
import com.amarhelper.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val health = FakeServiceHealthRepository()
    private val agents = FakeAgentRepository()

    private fun viewModel() = DashboardViewModel(health, agents)

    @Test
    fun `first load reports service counts and recent sessions`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.onlineServices)
        assertEquals(1, state.configuredServices)
        assertEquals(1, state.recentSessions.size)
    }

    @Test
    fun `only active sessions count towards the active total`() = runTest {
        agents.sessionsResult = ApiResult.Success(
            listOf(
                FakeAgentRepository.session("a", TaskState.RUNNING),
                FakeAgentRepository.session("b", TaskState.COMPLETED),
                FakeAgentRepository.session("c", TaskState.QUEUED),
                FakeAgentRepository.session("d", TaskState.FAILED),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.activeSessions.size)
    }

    @Test
    fun `an empty configuration is reported so the UI can offer setup`() = runTest {
        health.health = ServiceId.entries.map { ServiceHealth.notConfigured(it) }
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.hasConfiguration)
    }

    @Test
    fun `a session listing failure does not hide service health`() = runTest {
        agents.sessionsResult = ApiResult.Failure(AppError.Offline())
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.sessionsError is AppError.Offline)
        assertEquals(1, vm.state.value.onlineServices)
    }

    @Test
    fun `a refresh that fails keeps the sessions already on screen`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.recentSessions.size)

        agents.sessionsResult = ApiResult.Failure(AppError.Timeout())
        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.recentSessions.size)
        assertTrue(vm.state.value.sessionsError is AppError.Timeout)
    }

    @Test
    fun `recovery after an outage clears the error`() = runTest {
        agents.sessionsResult = ApiResult.Failure(AppError.Offline())
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.sessionsError is AppError.Offline)

        agents.sessionsResult = ApiResult.Success(listOf(FakeAgentRepository.session("ses_9")))
        vm.refresh()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.sessionsError)
        assertEquals("ses_9", vm.state.value.recentSessions.first().id)
    }

    @Test
    fun `a degraded service is not counted as online`() = runTest {
        health.health = listOf(
            ServiceHealth(ServiceId.OPEN_CODE, HealthState.DEGRADED, detail = "Authentication failed"),
            ServiceHealth(ServiceId.OPEN_HANDS, HealthState.OFFLINE),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.onlineServices)
        assertEquals(2, vm.state.value.configuredServices)
    }

    @Test
    fun `at most five sessions reach the dashboard`() = runTest {
        agents.sessionsResult = ApiResult.Success((1..12).map { FakeAgentRepository.session("s$it") })
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(5, vm.state.value.recentSessions.size)
    }
}

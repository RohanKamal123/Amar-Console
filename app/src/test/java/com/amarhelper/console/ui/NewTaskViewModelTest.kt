package com.amarhelper.console.ui

import com.amarhelper.console.core.result.ApiResult
import com.amarhelper.console.core.result.AppError
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.fake.FakeAgentRepository
import com.amarhelper.console.ui.task.NewTaskViewModel
import com.amarhelper.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAgentRepository()

    private fun viewModel() = NewTaskViewModel(repository)

    @Test
    fun `providers load and the first one is preselected`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(AgentProvider.OPEN_CODE, AgentProvider.OPEN_HANDS), vm.state.value.providers)
        assertEquals(AgentProvider.OPEN_CODE, vm.state.value.selectedProvider)
        assertFalse(vm.state.value.isLoadingProviders)
    }

    @Test
    fun `submit is blocked until a prompt is entered`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.canSubmit)
        vm.onPromptChange("Build a REST API for user authentication")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `a too-short prompt is rejected without a network call`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("hi")
        vm.submit()
        advanceUntilIdle()

        assertNotNull(vm.state.value.validationMessage)
        assertTrue(repository.submittedTasks.isEmpty())
    }

    @Test
    fun `a valid prompt is submitted and the started session is exposed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("Build a REST API for user authentication.")
        vm.submit()
        advanceUntilIdle()

        assertEquals(1, repository.submittedTasks.size)
        assertEquals(AgentProvider.OPEN_CODE, repository.submittedTasks.first().provider)
        assertEquals("ses_1", vm.state.value.startedSession?.id)
        assertFalse(vm.state.value.isSubmitting)
    }

    @Test
    fun `the prompt is trimmed before it reaches the backend`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("   Build a REST API for user authentication.   ")
        vm.submit()
        advanceUntilIdle()

        assertEquals("Build a REST API for user authentication.", repository.submittedTasks.first().prompt)
    }

    @Test
    fun `a repository is only sent when the user supplied one`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onProviderSelected(AgentProvider.OPEN_HANDS)
        vm.onPromptChange("Build a REST API for user authentication.")
        vm.onRepositoryChange("  ")
        vm.submit()
        advanceUntilIdle()

        assertNull(repository.submittedTasks.first().repository)
    }

    @Test
    fun `a failed submission surfaces the error and clears the submitting state`() = runTest {
        repository.submitResult = ApiResult.Failure(AppError.Unauthorized())
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("Build a REST API for user authentication.")
        vm.submit()
        advanceUntilIdle()

        assertTrue(vm.state.value.error is AppError.Unauthorized)
        assertFalse(vm.state.value.isSubmitting)
        assertNull(vm.state.value.startedSession)
    }

    @Test
    fun `an unreachable backend leaves the user able to retry`() = runTest {
        repository.submitResult = ApiResult.Failure(AppError.Offline())
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("Build a REST API for user authentication.")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.retryable)

        repository.submitResult = ApiResult.Success(FakeAgentRepository.session("ses_2"))
        vm.submit()
        advanceUntilIdle()

        assertEquals("ses_2", vm.state.value.startedSession?.id)
    }

    @Test
    fun `an empty provider list keeps submit disabled`() = runTest {
        repository.providers = emptyList()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPromptChange("Build a REST API for user authentication.")

        assertFalse(vm.state.value.canSubmit)
    }
}

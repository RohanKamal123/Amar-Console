package com.amarhelper.console.ui

import androidx.test.core.app.ApplicationProvider
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.config.ServiceId
import com.amarhelper.console.domain.model.HealthState
import com.amarhelper.console.domain.model.ServiceHealth
import com.amarhelper.console.fake.FakeServiceHealthRepository
import com.amarhelper.console.ui.splash.SplashUiState
import com.amarhelper.console.ui.splash.SplashViewModel
import com.amarhelper.console.util.MainDispatcherRule
import com.amarhelper.console.util.awaitUntil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses a real DataStore-backed ConfigStore, so startup routing is exercised end to end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configStore = ConfigStore(context)
    private val health = FakeServiceHealthRepository()

    @After
    fun tearDown() = runTest { configStore.clear() }

    @Test
    fun `with nothing configured the user is sent to setup`() = runTest {
        configStore.clear()
        val vm = SplashViewModel(configStore, health)
        awaitUntil { vm.state.value is SplashUiState.NeedsSetup }

        assertTrue(vm.state.value is SplashUiState.NeedsSetup)
    }

    @Test
    fun `a reachable service lets the app continue`() = runTest {
        configStore.setUrl(ServiceId.OPEN_CODE, "https://box.tail1234.ts.net:4096")
        val vm = SplashViewModel(configStore, health)
        awaitUntil { vm.state.value !is SplashUiState.Initializing }

        assertTrue(vm.state.value.toString(), vm.state.value is SplashUiState.Ready)
    }

    @Test
    fun `when every configured service is down the user gets an actionable failure`() = runTest {
        configStore.setUrl(ServiceId.OPEN_CODE, "https://box.tail1234.ts.net:4096")
        health.health = listOf(
            ServiceHealth(ServiceId.OPEN_CODE, HealthState.OFFLINE, detail = "Connection refused."),
        )
        val vm = SplashViewModel(configStore, health)
        awaitUntil { vm.state.value !is SplashUiState.Initializing }

        val state = vm.state.value
        assertTrue(state is SplashUiState.Unreachable)
        assertTrue((state as SplashUiState.Unreachable).reason.contains("refused"))
    }

    @Test
    fun `a degraded service still counts as reachable`() = runTest {
        configStore.setUrl(ServiceId.OPEN_CODE, "https://box.tail1234.ts.net:4096")
        health.health = listOf(
            ServiceHealth(ServiceId.OPEN_CODE, HealthState.DEGRADED, detail = "Authentication failed."),
        )
        val vm = SplashViewModel(configStore, health)
        awaitUntil { vm.state.value !is SplashUiState.Initializing }

        assertTrue(vm.state.value.toString(), vm.state.value is SplashUiState.Ready)
    }
}

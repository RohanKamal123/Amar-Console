package com.amarhelper.console

import android.app.Application
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.net.ApiClientFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltAndroidApp
class AmarConsoleApplication : Application() {

    @Inject lateinit var configStore: ConfigStore

    @Inject lateinit var clientFactory: ApiClientFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Editing a service URL must not leave a Retrofit client pointing at the old host.
        configStore.config
            .distinctUntilChanged()
            .onEach { config ->
                clientFactory.verboseLogging = config.verboseNetworkLogging
                clientFactory.invalidate()
            }
            .launchIn(appScope)
    }
}

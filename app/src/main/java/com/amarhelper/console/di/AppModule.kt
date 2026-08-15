package com.amarhelper.console.di

import android.content.Context
import com.amarhelper.console.data.config.ConfigStore
import com.amarhelper.console.data.repository.DefaultAgentRepository
import com.amarhelper.console.data.repository.DefaultServiceHealthRepository
import com.amarhelper.console.data.security.SecureCredentialStore
import com.amarhelper.console.domain.repository.AgentRepository
import com.amarhelper.console.domain.repository.ServiceHealthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Unknown keys are ignored on purpose: these APIs add fields between releases, and
     * an extra field must never take the app down.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideConfigStore(@ApplicationContext context: Context): ConfigStore = ConfigStore(context)

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): SecureCredentialStore =
        SecureCredentialStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: DefaultAgentRepository): AgentRepository

    @Binds
    @Singleton
    abstract fun bindServiceHealthRepository(impl: DefaultServiceHealthRepository): ServiceHealthRepository
}

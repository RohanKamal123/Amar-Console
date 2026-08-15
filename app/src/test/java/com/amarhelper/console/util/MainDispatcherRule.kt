package com.amarhelper.console.util

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Replaces Dispatchers.Main so viewModelScope work runs on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: ContinuationInterceptor = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher as kotlinx.coroutines.CoroutineDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Waits for [predicate], draining the test scheduler between checks.
 *
 * DataStore does its I/O on real threads, so `advanceUntilIdle()` alone can return
 * before a configuration read has landed. This alternates virtual-time draining with a
 * short real sleep, bounded by [timeoutMillis], which makes such tests deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun kotlinx.coroutines.test.TestScope.awaitUntil(
    timeoutMillis: Long = 5_000,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        testScheduler.advanceUntilIdle()
        if (predicate()) return
        Thread.sleep(10)
    }
    testScheduler.advanceUntilIdle()
    check(predicate()) { "Condition was not met within ${timeoutMillis}ms" }
}

/**
 * Like [awaitUntil], but drains only tasks already due, without advancing virtual time.
 *
 * Use this when the code under test schedules work indefinitely — a poll loop, say —
 * where `advanceUntilIdle()` would never return because there is always another task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun kotlinx.coroutines.test.TestScope.awaitCurrent(
    timeoutMillis: Long = 5_000,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        testScheduler.runCurrent()
        if (predicate()) return
        Thread.sleep(10)
    }
    testScheduler.runCurrent()
    check(predicate()) { "Condition was not met within ${timeoutMillis}ms" }
}

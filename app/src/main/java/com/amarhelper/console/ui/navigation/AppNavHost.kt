package com.amarhelper.console.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.amarhelper.console.domain.model.AgentProvider
import com.amarhelper.console.ui.console.SessionConsoleScreen
import com.amarhelper.console.ui.dashboard.DashboardScreen
import com.amarhelper.console.ui.services.ServicesScreen
import com.amarhelper.console.ui.sessions.SessionsScreen
import com.amarhelper.console.ui.settings.SettingsScreen
import com.amarhelper.console.ui.splash.SplashScreen
import com.amarhelper.console.ui.task.NewTaskScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen(
                onReady = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onNeedsSetup = {
                    navController.navigate(Routes.SETTINGS) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onNewTask = { navController.navigate(Routes.NEW_TASK) },
                onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                onOpenServices = { navController.navigate(Routes.SERVICES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSession = { provider, id ->
                    navController.navigate(Routes.console(provider.name, id))
                },
            )
        }

        composable(Routes.NEW_TASK) {
            NewTaskScreen(
                onBack = { navController.popBackStack() },
                onTaskStarted = { provider, id ->
                    navController.navigate(Routes.console(provider.name, id)) {
                        popUpTo(Routes.NEW_TASK) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.SESSIONS) {
            SessionsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { provider, id ->
                    navController.navigate(Routes.console(provider.name, id))
                },
                onNewTask = { navController.navigate(Routes.NEW_TASK) },
            )
        }

        composable(Routes.SERVICES) {
            ServicesScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(
            route = Routes.CONSOLE,
            arguments = listOf(
                navArgument(Routes.Args.PROVIDER) { type = NavType.StringType },
                navArgument(Routes.Args.SESSION_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val provider = entry.arguments?.getString(Routes.Args.PROVIDER)
                ?.let { runCatching { AgentProvider.valueOf(it) }.getOrNull() }
                ?: AgentProvider.OPEN_CODE
            val sessionId = entry.arguments?.getString(Routes.Args.SESSION_ID).orEmpty()
            SessionConsoleScreen(
                provider = provider,
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

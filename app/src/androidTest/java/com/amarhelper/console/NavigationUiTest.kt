package com.amarhelper.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI coverage of the flows a JVM test cannot reach: real navigation, real
 * text input, real recomposition.
 *
 * These require a device or emulator. They are executed by the `instrumentation` job in
 * .github/workflows/android.yml; they have NOT been run in the container this project
 * was developed in, which has no KVM. See TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_fresh_install_lands_on_settings_and_offers_every_service() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("OpenHands").assertIsDisplayed()
        composeRule.onNodeWithText("OpenCode").assertIsDisplayed()
        composeRule.onNodeWithText("LiteLLM").assertIsDisplayed()
    }

    @Test
    fun a_public_cleartext_url_is_rejected_with_an_explanation() {
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Base URL").onFirst().performTextInput("http://example.com")
        composeRule.onAllNodesWithText("Save").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "Plain http is only allowed for private or tailnet hosts. Use https for example.com.",
        ).assertIsDisplayed()
    }

    @Test
    fun a_saved_credential_is_never_displayed_again() {
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Token or API key (optional)").onFirst()
            .performTextInput("super-secret-token-value")
        composeRule.onAllNodesWithText("Save").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("super-secret-token-value").assertCountEqualsZero()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
    fetchSemanticsNodes().also { nodes ->
        check(nodes.isEmpty()) { "A stored credential was rendered on screen" }
    }
}

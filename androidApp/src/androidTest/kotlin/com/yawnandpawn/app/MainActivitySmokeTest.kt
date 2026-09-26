package com.yawnandpawn.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke test (Gradle Managed Device in CI): the real app launches and shows its name.
 *
 * The name is an underscored sentence, not a backticked one: with minSdk 26 the test APK is
 * dexed below DEX 040, which rejects spaces in method names. Host tests keep backticked names.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launching_MainActivity_shows_the_app_name() {
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
    }
}

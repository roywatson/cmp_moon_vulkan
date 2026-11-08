package com.roywatson.lunar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AboutScreenTest {
    private val openedUris = mutableListOf<String>()
    private val recordingUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            openedUris += uri
        }
    }

    @Test
    fun aboutButtonOpensTheContactCardAndCloseDismissesIt() = runComposeUiTest {
        setContent { App() }
        onNodeWithText("rwatson@roywatson.com").assertDoesNotExist()

        onNode(hasContentDescription("About this app")).performClick()
        onNodeWithText("rwatson@roywatson.com").assertExists()
        onNode(hasContentDescription("Roy Watson, Software Development")).assertExists()

        onNodeWithText("CLOSE").performClick()
        onNodeWithText("rwatson@roywatson.com").assertDoesNotExist()
    }

    @Test
    fun contactDetailsOpenTheMatchingApps() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) { App() }
        }
        onNode(hasContentDescription("About this app")).performClick()

        onNodeWithText("1-802-735-1500").performClick()
        onNodeWithText("rwatson@roywatson.com").performClick()
        onNodeWithText("www.roywatson.app").performClick()

        assertEquals(
            listOf("tel:+18027351500", "mailto:rwatson@roywatson.com", "https://www.roywatson.app"),
            openedUris,
        )
    }
}

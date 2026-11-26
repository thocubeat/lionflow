package no.lionflow.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import no.lionflow.app.ui.theme.LionFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BluetoothDeviceDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun carsFirstAndScrollsToLastDevice() {
        val devices = List(39) { index -> "02:00:00:00:00:${index.toString(16).padStart(2, '0')}" to "Device $index" }
        val car = "02:00:00:00:00:ff" to "PEUGEOT"
        val duplicateName = "02:00:00:00:00:fe" to car.second
        var picked: Pair<String, String>? = null
        compose.setContent {
            LionFlowTheme("light") {
                BluetoothDeviceDialog(devices + duplicateName + car, onDismiss = {}) { address, name -> picked = address to name }
            }
        }
        // Devices that look like a car come first; two with the same name each return their own address.
        compose.onAllNodes(hasText(car.second))[1].assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(car, picked) }
        // The list scrolls to the last other device, and Cancel stays visible.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(devices.size + 3)
        compose.onNodeWithText("Device 38").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}

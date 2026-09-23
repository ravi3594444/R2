package ai.wakey.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishCheckTest {
    private val connectedDevices = FakeUi(
        "com.android.settings", "Settings",
        items = listOf("Connected devices", "Pair new device", "Bluetooth", "Saved devices", "NFC"),
        texts = setOf("Connected devices", "NFC"),
    ).observation()

    private val bluetoothPage = FakeUi(
        "com.android.settings", "Settings",
        items = listOf("Bluetooth", "Use Bluetooth", "Pair new device"),
        texts = setOf("Bluetooth"),
    ).observation()

    @Test
    fun extractsNavigationTargets() {
        assertEquals(listOf("settings", "bluetooth"), FinishCheck.targets("open Settings and find Bluetooth"))
        assertEquals(listOf("wi-fi"), FinishCheck.targets("Go to the Wi-Fi settings."))
        assertEquals(listOf("chrome"), FinishCheck.targets("open Chrome and search for cats"))
        assertEquals(emptyList<String>(), FinishCheck.targets("what's the time"))
    }

    @Test
    fun flagsTargetThatIsOnlyListed() {
        val correction = FinishCheck.unopenedTarget("open Settings and find Bluetooth", connectedDevices)
        assertTrue(correction!!.contains("[3] “Bluetooth”"))
    }

    @Test
    fun acceptsFinishOnTheTargetsPage() {
        assertNull(FinishCheck.unopenedTarget("open Settings and find Bluetooth", bluetoothPage))
    }

    @Test
    fun ignoresRequestsWithoutNavigation() {
        assertNull(FinishCheck.unopenedTarget("tell me a joke", connectedDevices))
        assertNull(FinishCheck.unopenedTarget("find Bluetooth", null))
    }
}

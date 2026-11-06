package suwayomi.tachidesk.fork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest

class ForkSettingsTest : ApplicationTest() {
    @Test
    fun `fork controls default to stock-safe disabled state`() {
        assertFalse(serverConfig.forkStockMode.value)
        assertFalse(serverConfig.mergeEnabled.value)
        assertFalse(serverConfig.duplicatesEnabled.value)
        assertFalse(serverConfig.reindexEnabled.value)
        assertFalse(serverConfig.doctorEnabled.value)
    }

    @Test
    fun `fork features are available until a fork migration fails`() {
        assertEquals(ForkFeatureAvailability.Status.AVAILABLE, ForkFeatureAvailability.status())
    }
}

package live.agor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductModeTest {
    @Test
    fun hermesOnlyDisablesAgorFeatures() {
        val mode = productModeFromBuildConfig(agorEnabled = false, productMode = "hermes-only")

        assertEquals(ProductKind.HermesOnly, mode.kind)
        assertTrue(mode.hermesOnly)
        assertFalse(mode.agorEnabled)
        assertEquals("hermes-only", mode.productId)
    }

    @Test
    fun hermesAgorKeepsAgorFeatures() {
        val mode = productModeFromBuildConfig(agorEnabled = true, productMode = "hermes-agor")

        assertEquals(ProductKind.HermesAgor, mode.kind)
        assertFalse(mode.hermesOnly)
        assertTrue(mode.agorEnabled)
        assertEquals("hermes-agor", mode.productId)
    }
}

package dev.flagkit.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VersionUtilsTest {

    @Test
    fun `parseVersion should parse valid semver string`() {
        val parsed = parseVersion("1.2.3")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
        assertEquals(2, parsed.minor)
        assertEquals(3, parsed.patch)
    }

    @Test
    fun `parseVersion should handle leading v prefix`() {
        val parsed = parseVersion("v1.2.3")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
        assertEquals(2, parsed.minor)
        assertEquals(3, parsed.patch)
    }

    @Test
    fun `parseVersion should handle uppercase V prefix`() {
        val parsed = parseVersion("V1.2.3")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
    }

    @Test
    fun `parseVersion should ignore pre-release suffix`() {
        val parsed = parseVersion("1.2.3-beta.1")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
        assertEquals(2, parsed.minor)
        assertEquals(3, parsed.patch)
    }

    @Test
    fun `parseVersion should return null for invalid versions`() {
        assertNull(parseVersion(null))
        assertNull(parseVersion(""))
        assertNull(parseVersion("   "))
        assertNull(parseVersion("invalid"))
        assertNull(parseVersion("1.2"))
        assertNull(parseVersion("1"))
        assertNull(parseVersion("a.b.c"))
    }

    @Test
    fun `compareVersions should compare major versions`() {
        assertTrue(compareVersions("1.0.0", "2.0.0") < 0)
        assertTrue(compareVersions("2.0.0", "1.0.0") > 0)
    }

    @Test
    fun `compareVersions should compare minor versions`() {
        assertTrue(compareVersions("1.1.0", "1.2.0") < 0)
        assertTrue(compareVersions("1.2.0", "1.1.0") > 0)
    }

    @Test
    fun `compareVersions should compare patch versions`() {
        assertTrue(compareVersions("1.0.1", "1.0.2") < 0)
        assertTrue(compareVersions("1.0.2", "1.0.1") > 0)
    }

    @Test
    fun `compareVersions should return 0 for equal versions`() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, compareVersions("v1.0.0", "1.0.0"))
    }

    @Test
    fun `compareVersions should return 0 for invalid versions`() {
        assertEquals(0, compareVersions(null, "1.0.0"))
        assertEquals(0, compareVersions("1.0.0", null))
        assertEquals(0, compareVersions("invalid", "1.0.0"))
        assertEquals(0, compareVersions("1.0.0", "invalid"))
    }

    @Test
    fun `isVersionLessThan should return true when first version is less`() {
        assertTrue(isVersionLessThan("1.0.0", "2.0.0"))
        assertTrue(isVersionLessThan("1.0.0", "1.1.0"))
        assertTrue(isVersionLessThan("1.0.0", "1.0.1"))
    }

    @Test
    fun `isVersionLessThan should return false when first version is greater or equal`() {
        assertFalse(isVersionLessThan("2.0.0", "1.0.0"))
        assertFalse(isVersionLessThan("1.0.0", "1.0.0"))
        assertFalse(isVersionLessThan("1.1.0", "1.0.0"))
    }

    @Test
    fun `isVersionLessThan should return false for invalid versions`() {
        assertFalse(isVersionLessThan(null, "1.0.0"))
        assertFalse(isVersionLessThan("invalid", "1.0.0"))
    }

    @Test
    fun `isVersionAtLeast should return true when first version is greater or equal`() {
        assertTrue(isVersionAtLeast("2.0.0", "1.0.0"))
        assertTrue(isVersionAtLeast("1.0.0", "1.0.0"))
        assertTrue(isVersionAtLeast("1.1.0", "1.0.0"))
    }

    @Test
    fun `isVersionAtLeast should return false when first version is less`() {
        assertFalse(isVersionAtLeast("1.0.0", "2.0.0"))
        assertFalse(isVersionAtLeast("1.0.0", "1.1.0"))
    }

    @Test
    fun `isVersionAtLeast should return true for invalid versions`() {
        // Returns 0 comparison which means >= is true
        assertTrue(isVersionAtLeast(null, "1.0.0"))
        assertTrue(isVersionAtLeast("invalid", "1.0.0"))
    }

    @Test
    fun `extension function versionLessThan should work correctly`() {
        assertTrue("1.0.0".versionLessThan("2.0.0"))
        assertFalse("2.0.0".versionLessThan("1.0.0"))
    }

    @Test
    fun `extension function versionAtLeast should work correctly`() {
        assertTrue("2.0.0".versionAtLeast("1.0.0"))
        assertTrue("1.0.0".versionAtLeast("1.0.0"))
        assertFalse("1.0.0".versionAtLeast("2.0.0"))
    }

    @Test
    fun `should handle complex version comparisons`() {
        assertTrue(isVersionLessThan("0.9.9", "1.0.0"))
        assertTrue(isVersionLessThan("1.9.9", "2.0.0"))
        assertTrue(isVersionLessThan("1.0.9", "1.1.0"))
        assertFalse(isVersionLessThan("10.0.0", "9.9.9"))
    }

    @Test
    fun `parseVersion should handle leading whitespace`() {
        val parsed = parseVersion("  1.2.3")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
        assertEquals(2, parsed.minor)
        assertEquals(3, parsed.patch)
    }

    @Test
    fun `parseVersion should handle trailing whitespace`() {
        val parsed = parseVersion("1.2.3  ")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
    }

    @Test
    fun `parseVersion should handle surrounding whitespace`() {
        val parsed = parseVersion("  1.2.3  ")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
    }

    @Test
    fun `parseVersion should handle v prefix with whitespace`() {
        val parsed = parseVersion("  v1.0.0  ")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
    }

    @Test
    fun `parseVersion should return null for version exceeding max`() {
        assertNull(parseVersion("1000000000.0.0"))
        assertNull(parseVersion("0.1000000000.0"))
        assertNull(parseVersion("0.0.1000000000"))
    }

    @Test
    fun `parseVersion should parse version at max boundary`() {
        val parsed = parseVersion("999999999.999999999.999999999")
        assertNotNull(parsed)
        assertEquals(999999999, parsed!!.major)
        assertEquals(999999999, parsed.minor)
        assertEquals(999999999, parsed.patch)
    }

    @Test
    fun `parseVersion should handle build metadata`() {
        val parsed = parseVersion("1.0.0+build.123")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.major)
        assertEquals(0, parsed.minor)
        assertEquals(0, parsed.patch)
    }
}

package dev.flagkit.utils

/**
 * Semantic version comparison utilities for SDK version metadata handling.
 *
 * These utilities are used to compare the current SDK version against
 * server-provided version requirements (min, recommended, latest).
 */

/**
 * Parsed semantic version components.
 */
data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
)

/** Maximum allowed value for version components (defensive limit). */
private const val MAX_VERSION_COMPONENT = 999_999_999

/**
 * Parse a semantic version string into numeric components.
 * Returns null if the version is not a valid semver.
 *
 * @param version The version string to parse (e.g., "1.2.3" or "v1.2.3")
 * @return ParsedVersion if valid, null otherwise
 */
fun parseVersion(version: String?): ParsedVersion? {
    if (version.isNullOrBlank()) {
        return null
    }

    // Trim whitespace
    val trimmed = version.trim()

    // Strip leading 'v' or 'V' if present
    val normalized = if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
        trimmed.substring(1)
    } else {
        trimmed
    }

    // Match semver pattern (allows pre-release suffix but ignores it for comparison)
    val regex = Regex("""^(\d+)\.(\d+)\.(\d+)""")
    val match = regex.find(normalized) ?: return null

    return try {
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()

        // Validate components are within reasonable bounds
        if (major < 0 || major > MAX_VERSION_COMPONENT ||
            minor < 0 || minor > MAX_VERSION_COMPONENT ||
            patch < 0 || patch > MAX_VERSION_COMPONENT) {
            return null
        }

        ParsedVersion(major, minor, patch)
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Compare two semantic versions.
 *
 * @param a First version string
 * @param b Second version string
 * @return negative number if a < b, 0 if a == b, positive number if a > b.
 *         Returns 0 if either version is invalid.
 */
fun compareVersions(a: String?, b: String?): Int {
    val parsedA = parseVersion(a)
    val parsedB = parseVersion(b)

    if (parsedA == null || parsedB == null) {
        return 0
    }

    // Compare major
    if (parsedA.major != parsedB.major) {
        return parsedA.major - parsedB.major
    }

    // Compare minor
    if (parsedA.minor != parsedB.minor) {
        return parsedA.minor - parsedB.minor
    }

    // Compare patch
    return parsedA.patch - parsedB.patch
}

/**
 * Check if version a is less than version b.
 *
 * @param a First version string
 * @param b Second version string
 * @return true if a < b, false otherwise (including when either version is invalid)
 */
fun isVersionLessThan(a: String?, b: String?): Boolean {
    return compareVersions(a, b) < 0
}

/**
 * Check if version a is greater than or equal to version b.
 *
 * @param a First version string
 * @param b Second version string
 * @return true if a >= b, false otherwise (returns true when either version is invalid)
 */
fun isVersionAtLeast(a: String?, b: String?): Boolean {
    return compareVersions(a, b) >= 0
}

/**
 * Extension function to check if this version is less than another.
 */
@JvmName("isVersionLessThanExt")
fun String.versionLessThan(other: String): Boolean {
    return isVersionLessThan(this, other)
}

/**
 * Extension function to check if this version is at least another version.
 */
@JvmName("isVersionAtLeastExt")
fun String.versionAtLeast(other: String): Boolean {
    return isVersionAtLeast(this, other)
}

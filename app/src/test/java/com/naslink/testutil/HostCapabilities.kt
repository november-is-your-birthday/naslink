package com.naslink.testutil

import java.nio.file.Files
import org.junit.Assume.assumeTrue

/**
 * Capability probes for tests that exercise host-OS semantics (symbolic links,
 * POSIX permissions, `sh`) that a Windows checkout may not provide.
 *
 * The guards probe real capabilities instead of the OS name, so a Windows host
 * with Developer Mode still runs the symlink tests, and every skip shows up as
 * "skipped" in the test report rather than silently shrinking coverage.
 */
object HostCapabilities {

    /** True when the current user may create symbolic links (always true on POSIX hosts). */
    val canCreateSymlinks: Boolean by lazy { probeSymlink() }

    /** True when the host offers POSIX semantics: `sh` on PATH and `/`-absolute paths. */
    val isPosixHost: Boolean by lazy {
        !System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true) &&
            runCatching { ProcessBuilder("sh", "-c", "true").start().waitFor() == 0 }.getOrDefault(false)
    }

    /** True when POSIX permission APIs work on this filesystem. */
    val supportsPosixPermissions: Boolean by lazy { probePosixPermissions() }

    fun assumeSymlinkCapable() = assumeTrue("host cannot create symbolic links", canCreateSymlinks)

    fun assumePosixHost() = assumeTrue("test requires a POSIX host (sh, /-absolute paths)", isPosixHost)

    fun assumePosixPermissions() = assumeTrue("host does not support POSIX permissions", supportsPosixPermissions)

    private fun probeSymlink(): Boolean = runCatching {
        val target = Files.createTempFile("naslink-symlink-probe", null)
        val link = target.resolveSibling("naslink-symlink-probe-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(link, target)
            true
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(target)
        }
    }.getOrDefault(false)

    private fun probePosixPermissions(): Boolean = runCatching {
        val probe = Files.createTempFile("naslink-posix-probe", null)
        try {
            Files.setPosixFilePermissions(probe, emptySet())
        } finally {
            Files.deleteIfExists(probe)
        }
        true
    }.getOrDefault(false)
}

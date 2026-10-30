package com.carlink.ocbm

import com.carlink.convert.InstallerImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Reading what an update package says of itself, from a package the enigy/ocbm builder made. */
@RunWith(RobolectricTestRunner::class)
class UpdatePackageFileTest {
    @Test
    fun `the builder's package says its version, the oldest it updates and the flash it needs`() {
        assertEquals(UpdateFixtures.PACKAGE_1_1_0_SHA256, InstallerImage.sha256Hex(UpdateFixtures.PACKAGE_1_1_0))
        assertEquals(UpdatePackage(version = "1.1.0", fromMin = "1.0.0", needKb = 387), UpdatePackageFile.read(UpdateFixtures.PACKAGE_1_1_0))
    }

    @Test
    fun `a package cut short is not read`() {
        val tar = gunzip(UpdateFixtures.PACKAGE_1_1_0)
        assertNull(UpdatePackageFile.read(UpdateFixtures.PACKAGE_1_1_0.copyOf(UpdateFixtures.PACKAGE_1_1_0.size / 2)))
        // Whole gzip, but the tar stops inside the first entry's data.
        assertNull(UpdatePackageFile.read(gzip(tar.copyOf(BLOCK + 4))))
    }

    @Test
    fun `anything that is not a gzipped package is not read`() {
        assertNull(UpdatePackageFile.read(ByteArray(0)))
        assertNull(UpdatePackageFile.read("1.1.0 ocbm new\n".toByteArray()))
        assertNull(UpdatePackageFile.read(gzip(ByteArray(2 * BLOCK))))
    }

    @Test
    fun `a package without its VERSION is not read`() {
        val tar = gunzip(UpdateFixtures.PACKAGE_1_1_0)
        // VERSION is the first entry: rename it, and fix nothing else, as the reader checks no header sums.
        val renamed = tar.copyOf().also { "VERSIOX".toByteArray().copyInto(it) }
        assertNull(UpdatePackageFile.read(gzip(renamed)))
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private companion object {
        const val BLOCK = 512
    }
}

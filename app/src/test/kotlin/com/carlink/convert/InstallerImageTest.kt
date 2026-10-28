package com.carlink.convert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The installer is checked before a byte goes to the adapter: its SHA-256, and its size when pinned. */
@RunWith(RobolectricTestRunner::class)
class InstallerImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val image = "an OCBM installer".toByteArray()
    private val hash = InstallerImage.sha256Hex(image)

    @Test
    fun `SHA-256 is lower-case hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", InstallerImage.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun `a sha256 file's hash is its first 64-digit word, as sha256sum writes it`() {
        assertEquals(hash, InstallerImage.hashIn("$hash  A15W_Update.img\n"))
        assertEquals(hash, InstallerImage.hashIn(hash.uppercase()))
        assertNull(InstallerImage.hashIn("A15W_Update.img: no hash here"))
    }

    @Test
    fun `the check passes only the right bytes`() {
        assertArrayEquals(image, InstallerImage.checked(image, hash).getOrThrow())
        assertArrayEquals(image, InstallerImage.checked(image, hash.uppercase(), image.size.toLong()).getOrThrow())
        assertTrue(InstallerImage.checked(image + 0.toByte(), hash).isFailure)
        assertTrue("pinned size", InstallerImage.checked(image, hash, image.size + 1L).isFailure)
    }

    @Test
    fun `a debug build reads the bench copy beside its sha256 file`() {
        val store = InstallerImageStore(context, pinned = null, bench = true)
        val folder = File(store.benchFolder!!)
        folder.listFiles()?.forEach { it.delete() }
        assertFalse(store.available())
        File(folder, InstallerImage.FILE_NAME).writeBytes(image)
        assertTrue(store.available())
        assertTrue("no sha256 file beside it", store.obtain { _, _ -> }.isFailure)
        File(folder, "${InstallerImage.FILE_NAME}.sha256").writeText("$hash  ${InstallerImage.FILE_NAME}\n")
        assertArrayEquals(image, store.obtain { _, _ -> }.getOrThrow())
        File(folder, "${InstallerImage.FILE_NAME}.sha256").writeText(InstallerImage.sha256Hex("other".toByteArray()))
        assertTrue("wrong hash", store.obtain { _, _ -> }.isFailure)
    }

    @Test
    fun `a release build has no bench copy and nothing to send until an image is pinned`() {
        val store = InstallerImageStore(context, pinned = null, bench = false)
        assertNull(store.benchFolder)
        assertFalse(store.available())
        assertTrue(store.obtain { _, _ -> }.isFailure)
    }

    @Test
    fun `a pinned image already downloaded is used from the cache once it checks out`() {
        val pinned = PinnedImage("https://invalid.example/A15W_Update.img", image.size.toLong(), hash, "1.0.0")
        File(context.cacheDir, "ocbm").mkdirs()
        File(context.cacheDir, "ocbm/$hash.img").writeBytes(image)
        val store = InstallerImageStore(context, pinned = pinned, bench = false)
        assertTrue(store.available())
        assertArrayEquals(image, store.obtain { _, _ -> }.getOrThrow())
    }
}

package com.gdhapp.client

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gdhapp.client.vpn.core.BundledRoutingAssets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutingAssetsTest {
    @Test fun installsRepairsAndKeepsValidRoutingFiles() {
        val app = ApplicationProvider.getApplicationContext<GdhappApplication>()
        val directory = java.io.File(app.cacheDir, "geodata-regression-${System.nanoTime()}")
        directory.mkdirs()
        try {
            directory.resolve("geoip.dat").writeText("corrupt old file")
            val installer = BundledRoutingAssets(app, directory)
            installer.install()
            assertTrue(installer.filesPresent())
            val geoip = directory.resolve("geoip.dat")
            val validTimestamp = geoip.lastModified()
            installer.install()
            assertEquals("Valid data must not be rewritten", validTimestamp, geoip.lastModified())
            val originalFirstByte = geoip.inputStream().use { it.read() }
            java.io.RandomAccessFile(geoip, "rw").use { it.write(originalFirstByte xor 255) }
            installer.install()
            assertEquals("Same-size corrupted file must be repaired", originalFirstByte, geoip.inputStream().use { it.read() })
            directory.resolve("geosite.dat").delete()
            assertFalse(installer.filesPresent())
            installer.install()
            assertTrue(installer.filesPresent())
            assertEquals(23_258_601L, geoip.length())
            assertEquals(2_330_365L, directory.resolve("geosite.dat").length())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}

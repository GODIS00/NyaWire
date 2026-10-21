package com.gdhapp.client.vpn.core

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Xray reads geodata from initCoreEnv's directory, not directly from APK assets. */
internal class BundledRoutingAssets(private val context: Context, private val directory: File) {
    private data class Asset(val name: String, val size: Long, val sha256: String)

    // Must match native/artifacts.json. Validate existing files once per process/update.
    private val assets = listOf(
        Asset("geoip.dat", 23_258_601, "4ff4b2ec242a9e87b296ba27ddb85f8311e8d3e071eaebac5be9231c60e408dd"),
        Asset("geosite.dat", 2_330_365, "37c030c086c57da4d0987c6be836f8ae4f8957cd904cfa0bc6bb6036bb61c816"),
    )

    fun filesPresent(): Boolean = assets.all { directory.resolve(it.name).let { file -> file.isFile && file.length() == it.size } }

    fun install() {
        check(directory.isDirectory || directory.mkdirs()) { AppText.text(R.string.could_not_create_the_xray_data_folder) }
        for (asset in assets) {
            val destination = AtomicFile(directory.resolve(asset.name))
            val valid = runCatching {
                destination.openRead().use { input ->
                    input.channel.size() == asset.size && digest(input) == asset.sha256
                }
            }.getOrDefault(false)
            if (valid) continue
            var output: java.io.FileOutputStream? = null
            try {
                val stream = destination.startWrite().also { output = it }
                val hash = MessageDigest.getInstance("SHA-256")
                context.assets.open(asset.name).use { input ->
                    java.security.DigestInputStream(input, hash).use { it.copyTo(stream, 64 * 1024) }
                }
                check(stream.channel.size() == asset.size && hash.digest().hex() == asset.sha256) { AppText.text(R.string.bundled_file_is_corrupted, asset.name) }
                destination.finishWrite(stream)
            } catch (error: Throwable) {
                output?.let(destination::failWrite)
                throw IllegalStateException(AppText.text(R.string.could_not_install_for_xray, asset.name, error.message), error)
            }
        }
    }

    private fun digest(input: java.io.InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        return hash.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

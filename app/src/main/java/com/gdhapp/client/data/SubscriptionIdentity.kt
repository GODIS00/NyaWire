package com.gdhapp.client.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/** App-scoped identity; does not read IMEI, serial numbers or advertising identifiers. */
class SubscriptionIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("subscription_identity", Context.MODE_PRIVATE)
    val hwid: String = synchronized(SubscriptionIdentity::class.java) {
        preferences.getString("hwid", null) ?: run {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            val source = androidId ?: UUID.randomUUID().toString()
            MessageDigest.getInstance("SHA-256").digest("GDHAPP:$source".toByteArray())
                .joinToString("") { "%02x".format(it) }.also { preferences.edit().putString("hwid", it).commit() }
        }
    }
    fun headers(): Map<String, String> = mapOf(
        "x-hwid" to hwid,
        "x-device-os" to "Android",
        "x-ver-os" to safeHeader(Build.VERSION.RELEASE),
        "x-device-model" to safeHeader("${Build.MANUFACTURER} ${Build.MODEL}"),
    )
    private fun safeHeader(value: String) = value.filter { it in ' '..'~' }.take(120).ifBlank { "Android" }
}

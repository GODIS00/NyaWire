package com.gdhapp.client.domain.model

import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

data class SubscriptionGroup(
    val id: String,
    val title: String,
    val countryCode: String = "UN",
    val isPinned: Boolean = false,
    val lastUpdated: String,
    val autoUpdateHours: Int = 1,
    val usedTrafficText: String = "0B",
    val totalTrafficText: String = "∞",
    val expiryDateText: String = "—",
    val supportUrl: String? = null,
    val announcementText: String = "",
    val isExpanded: Boolean = true,
    val servers: List<ServerNode>,
    val sourceUrl: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val trafficFraction: Float? = null,
)

data class RemovedServer(val group: SubscriptionGroup, val node: ServerNode, val groupIndex: Int, val nodeIndex: Int)

@androidx.compose.runtime.Immutable
data class ServerNode(
    val id: String,
    val name: String,
    val flagResOrCode: String = "UN",
    val protocolSummary: String,
    val rawConfigJson: String,
    val pingMs: Int? = null,
    val isSelected: Boolean = false,
    val host: String? = null,
    val port: Int? = null,
    val serverName: String? = null,
    val isFavorite: Boolean = false,
    val probeResults: Map<ProbeMethod, ProbeResult> = emptyMap(),
)

enum class CoreEngine { AUTO, XRAY, SING_BOX;
    val label get() = when (this) { AUTO -> AppText.text(R.string.auto); XRAY -> "Xray"; SING_BOX -> "sing-box" }
}
enum class ProbeMethod(val label: String, val usesProxy: Boolean = false) {
    TCP("TCP"), ICMP("ICMP"), HTTP_GET("HTTP GET", true), HTTP_HEAD("HTTP HEAD", true),
    TLS("TLS"), DNS("DNS");
}

data class ProbeResult(
    val method: ProbeMethod,
    val latencyMs: Int? = null,
    val error: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    val httpStatus: Int? = null,
    val samplesMs: List<Int> = emptyList(),
) { val isSuccess get() = latencyMs != null && error == null }

data class VpnMetrics(
    val connectedAt: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val engine: String = "",
    val localPort: Int = 0,
    val proxyOnly: Boolean = false,
    val trafficAvailable: Boolean = true,
)

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data object Connected : VpnState
    data class Error(val message: String) : VpnState
}

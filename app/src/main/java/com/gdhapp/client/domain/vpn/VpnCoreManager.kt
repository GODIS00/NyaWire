package com.gdhapp.client.domain.vpn

import com.gdhapp.client.domain.model.ClientSettings

/** Runs the bundled core with native Xray TUN or the HEV bridge. The service owns tunFd. */
interface VpnCoreManager {
    val name: String
    val localPort: Int
    val publicSocksAvailable: Boolean get() = true
    val isRunning: Boolean
    suspend fun start(config: String, tunFd: Int, settings: ClientSettings)
    suspend fun stop()
    fun trafficTotals(): Pair<Long, Long>
}

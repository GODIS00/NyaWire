package com.gdhapp.client.vpn

/** Three consecutive failures on one stable network; a success or network change clears the streak. */
internal class FailoverTracker(private val graceMs: Long = 15_000) {
    private var network: String? = null
    private var since = 0L
    private var failures = 0
    fun ready(currentNetwork: String?, now: Long): Boolean {
        if (network != currentNetwork) { network = currentNetwork; since = now; failures = 0 }
        if (currentNetwork == null) { failures = 0; return false }
        return now - since >= graceMs
    }
    fun record(success: Boolean): Boolean {
        failures = if (success) 0 else failures + 1
        return failures >= 3
    }
    fun reset() { network = null; failures = 0 }
}

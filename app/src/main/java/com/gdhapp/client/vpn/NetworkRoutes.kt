package com.gdhapp.client.vpn

object NetworkRoutes {
    private val excluded = listOf("0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/3")

    fun publicIpv4Routes(): List<Pair<String, Int>> {
        val ranges = excluded.map { cidr ->
            val ip = ipv4(cidr.substringBefore('/'))
            val count = 1L shl (32 - cidr.substringAfter('/').toInt())
            ip to ip + count - 1
        }.sortedBy { it.first }
        val allowed = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        for ((low, high) in ranges) {
            if (start < low) allowed.add(start to low - 1)
            start = maxOf(start, high + 1)
        }
        if (start <= 0xFFFFFFFFL) allowed.add(start to 0xFFFFFFFFL)
        return allowed.flatMap { (low, high) -> cidrs(low, high) }
    }

    private fun cidrs(low: Long, high: Long): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var cursor = low
        while (cursor <= high) {
            var size = if (cursor == 0L) 1L shl 32 else cursor and -cursor
            while (size > high - cursor + 1) size = size shr 1
            val prefix = 32 - java.lang.Long.numberOfTrailingZeros(size)
            result.add(formatIpv4(cursor) to prefix)
            cursor += size
        }
        return result
    }
    private fun ipv4(value: String): Long = value.split('.').fold(0L) { total, part -> (total shl 8) or part.toLong() }
    private fun formatIpv4(value: Long) = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
    fun isLiteralAddress(value: String): Boolean = if (':' in value) {
        value.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(value) }.isSuccess
    } else value.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && it.isNotEmpty() } }
}

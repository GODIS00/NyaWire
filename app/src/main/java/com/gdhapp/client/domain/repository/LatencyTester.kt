package com.gdhapp.client.domain.repository

import com.gdhapp.client.domain.model.ServerNode
import com.gdhapp.client.domain.model.ClientSettings
import com.gdhapp.client.domain.model.ProbeMethod
import com.gdhapp.client.domain.model.ProbeResult

interface LatencyTester {
    suspend fun test(node: ServerNode, method: ProbeMethod, settings: ClientSettings): ProbeResult
}

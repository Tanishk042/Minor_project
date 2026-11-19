package org.forestear.node.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.forestear.node.audio.CollectionStats
import org.forestear.node.decision.Decision
import org.forestear.node.diagnostics.BenchmarkReport
import org.forestear.node.diagnostics.SelfTestReport
import org.forestear.node.model.WindowResult
import org.forestear.node.power.BatterySnapshot

data class NodeStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val modelLoaded: Boolean = false,
    val serviceStartedAtMs: Long = 0L,
    val uptimeMs: Long = 0L,
    val restartCount: Int = 0,
    val audioRestartCount: Int = 0,
    val droppedFrames: Long = 0L,
    val activeInput: String = "not started",
    val effects: String = "",
    val battery: BatterySnapshot? = null,
    val signalDbm: Int? = null,
    val networkType: String = "unknown",
    val governorLevel: String = "NORMAL",
    val governorReason: String = "",
    val lastWindow: WindowResult? = null,
    val lastDecisions: List<Decision> = emptyList(),
    val lastEvent: String? = null,
    val backendOnline: Boolean = false,
    val queueSize: Int = 0,
    val alertsSent: Long = 0L,
    val smsStatus: String = "never",
    val collection: CollectionStats? = null,
    val streaming: Boolean = false,
    val soak: Boolean = false,
    val protection: ProtectionReport? = null,
    val selfTest: SelfTestReport? = null,
    val benchmark: BenchmarkReport? = null,
    val lastError: String? = null,
    val lastHeartbeatMs: Long = 0L,
    val inferenceMs: Double = 0.0,
    val adaptiveStride: Int = 1,
    val deviceHeadline: String = "",
    val tier: String = "—",
    val tierReason: String = "",
    val runtimeHopS: Double = 0.5,
    val runtimeReason: String = "",
    val degraded: Boolean = false,
    val degradedReason: String = "",
    val clockOffsetMs: Long? = null,
    val freeStorageMb: Int = 0,
    val continuity: String = "",
    val audioRate: Int = 16000,
    val resampling: Boolean = false,
    val capabilitiesMissing: List<String> = emptyList(),
    val networkTransport: String = "unknown",
    val streamingOnly: Boolean = false,
) {
    val threatScore: Float
        get() = lastWindow?.threatScore ?: 0f
}

/**
 * In-process observable state. The foreground service owns it; Compose screens
 * collect it. Keeping this outside the service makes the UI survive a service
 * restart without a binder.
 */
object NodeState {
    private val _flow = MutableStateFlow(NodeStatus())
    val flow: StateFlow<NodeStatus> = _flow

    fun update(block: (NodeStatus) -> NodeStatus) {
        _flow.value = block(_flow.value)
    }

    fun reset() {
        _flow.value = NodeStatus()
    }
}

package com.nuvio.tv.ui.screens.player

import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.net.ProtocolException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * A server that will not take a second download from the same viewer: a piece request refused (401, 403) or reset or
 * cut off once open, while another download to that server keeps delivering. When every download fails together it
 * is an outage or an expired link, and nothing is learnt. "Too many requests" (429, 503) keeps its own rule.
 */
internal object ExtraDownloadRefusal {
    /** Another piece counts as delivering when its bytes arrived this recently. */
    const val DELIVERING_WITHIN_MS = 3_000L

    private val REFUSED_CODES = setOf(401, 403)

    fun isRefusal(error: Throwable): Boolean {
        if (PlaybackNetworkFailure.isConnectFailure(error)) return false
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> return cause.responseCode in REFUSED_CODES
                is EOFException -> return true
                is ProtocolException -> return cause.message.orEmpty().contains("unexpected end of stream", true)
                is SocketException -> {
                    val message = cause.message.orEmpty()
                    return message.contains("reset", true) || message.contains("broken pipe", true)
                }
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /** True when a piece other than [refused] delivered bytes within [DELIVERING_WITHIN_MS] of [nowMs]. */
    fun othersDelivering(deliveredAtMs: Map<Long, Long>, refused: Long, nowMs: Long): Boolean =
        deliveredAtMs.any { (piece, atMs) -> piece != refused && nowMs - atMs in 0..DELIVERING_WITHIN_MS }
}

/**
 * Strikes per server. The first refused extra download holds the server to one download fewer than were running; a
 * second within [holdMs] holds it to one. Refusals within [burstMs] of a strike (a retry of the same piece, pieces
 * refused together) belong to that strike.
 */
internal class ExtraDownloadStrikes(
    private val holdMs: Long = HostSpeeds.LIMIT_EASE_MS,
    private val burstMs: Long = 10_000L
) {
    private data class Strike(val atMs: Long, val limit: Int)

    private val strikes = ConcurrentHashMap<String, Strike>()

    /** The downloads to hold [key] to after it refused an extra one with [running] requests running (it included). */
    fun limitAfter(key: String, running: Int, nowMs: Long): Int {
        val previous = strikes[key]
        val limit = when {
            previous == null || nowMs - previous.atMs >= holdMs -> (running - 1).coerceAtLeast(1)
            nowMs - previous.atMs < burstMs -> return previous.limit
            else -> 1
        }
        strikes[key] = Strike(nowMs, limit)
        return limit
    }

    fun clear() = strikes.clear()
}

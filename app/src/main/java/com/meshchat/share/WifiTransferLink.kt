package com.meshchat.share

import android.content.Context
import com.meshchat.call.WifiDirectGroup
import com.meshchat.core.LinkParams
import com.meshchat.core.SecureStream
import com.meshchat.core.StreamSession
import com.meshchat.core.TcpSessions
import com.meshchat.core.TransferLink

/** File transfer over Wi-Fi Direct: 5 GHz preferred, high-throughput Wi-Fi mode, one encrypted TCP connection. */
class WifiTransferLink(ctx: Context) : TransferLink {
    private val group = WifiDirectGroup(ctx, highThroughput = true)

    override suspend fun host(): LinkParams? = group.host(prefer5Ghz = com.meshchat.data.AppSettings.use5Ghz.value)

    override suspend fun join(p: LinkParams): Boolean = group.join(p)

    override suspend fun openStream(session: StreamSession): SecureStream? {
        return if (session.isHost) {
            val server = group.server ?: return null
            TcpSessions.accept(server, session, 30_000).also { if (it == null) com.meshchat.core.MeshLog.log("files: nobody connected to the TCP port") }
        } else {
            val addr = group.hostAddress ?: return null
            TcpSessions.connect(addr, WifiDirectGroup.PORT, session, 20_000).also { if (it == null) com.meshchat.core.MeshLog.log("files: could not reach ${addr.hostAddress}") }
        }
    }

    override fun close() = group.close()
}

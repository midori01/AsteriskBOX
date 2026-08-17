// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.os.Looper
import app.ProjectInfo
import engine.singbox.SingBoxControlConfig
import features.logs.AndroidCoreLogRepository
import features.logs.currentLogTime
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import features.logs.AndroidAppLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class SingBoxStatusMessage(
    val memory: Long = 0L,
    val uplink: Long = 0L,
    val downlink: Long = 0L,
    val uplinkTotal: Long = 0L,
    val downlinkTotal: Long = 0L,
    val trafficAvailable: Boolean = false,
)

internal data class SingBoxCommandTarget(
    val local: Boolean,
    val control: SingBoxControlConfig,
)

internal interface SingBoxCommandListener {
    fun onConnected()
    fun onDisconnected(message: String)
    fun onStatus(status: SingBoxStatusMessage)
    fun onProxies(proxies: SingBoxProxiesState)
    fun onConnections(connections: SingBoxConnectionsState)
}

internal class SingBoxCommandClient(
    private val target: SingBoxCommandTarget,
    private val listener: SingBoxCommandListener,
) {
    private val logWriter = SingBoxCommandLogWriter()
    private val isClosed = AtomicBoolean(false)
    private val hasDisconnected = AtomicBoolean(false)
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private var clientScope: CoroutineScope? = null

    private val connectionsLock = Any()
    private val connectionMap = linkedMapOf<String, MutableConnection>()

    var version: String = ProjectInfo.SING_BOX_VERSION
        private set

    fun connect() {
        disconnect()
        isClosed.set(false)
        hasDisconnected.set(false)

        // Probe connectivity by calling GetStartedAt
        serviceStartedAtMillis()

        // Fetch version and default log level
        runCatching { fetchRemoteVersion() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ver ->
            version = ver
        }
        runCatching {
            val level = getDefaultLogLevel()
            logWriter.setDefaultLogLevel(level)
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = scope

        listener.onConnected()

        startStatusStream(scope)
        startGroupsStream(scope)
        startConnectionsStream(scope)
        startLogStream(scope)
        startHealthCheck(scope)
    }

    fun disconnect() {
        isClosed.set(true)
        clientScope?.cancel()
        clientScope = null
        synchronized(activeConnections) {
            for (conn in activeConnections) {
                runCatching { conn.disconnect() }
            }
            activeConnections.clear()
        }
        synchronized(connectionsLock) {
            connectionMap.clear()
        }
    }

    fun selectOutbound(groupTag: String, outboundTag: String) {
        val writer = ProtoWriter()
        writer.writeString(1, groupTag)
        writer.writeString(2, outboundTag)
        executeUnary("SelectOutbound", writer.toByteArray())
    }

    fun urlTest(groupTag: String) {
        val writer = ProtoWriter()
        writer.writeString(1, groupTag)
        executeUnary("URLTest", writer.toByteArray())
    }

    fun closeConnection(connectionId: String) {
        val writer = ProtoWriter()
        writer.writeString(1, connectionId)
        executeUnary("CloseConnection", writer.toByteArray())
    }

    fun closeConnections() {
        executeUnary("CloseAllConnections")
    }

    fun setMode(mode: String) {
        val writer = ProtoWriter()
        writer.writeString(3, mode.toOfficialClashMode())
        executeUnary("SetClashMode", writer.toByteArray())
    }

    fun reloadService() {
        // Supervised restart is required for ROOT service per AsteriskBOX guidelines
    }

    fun serviceStartedAtMillis(): Long {
        val payload = executeUnary("GetStartedAt")
        val reader = ProtoReader(payload)
        var startedAt = 0L
        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            if (tag == 1 && wireType == 0) {
                startedAt = reader.readVarint()
            } else {
                reader.skipField(wireType)
            }
        }
        return startedAt
    }

    private fun fetchRemoteVersion(): String {
        val payload = executeUnary("GetVersion")
        val reader = ProtoReader(payload)
        var ver = ""
        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            if (tag == 1 && wireType == 2) {
                ver = reader.readString()
            } else {
                reader.skipField(wireType)
            }
        }
        return ver
    }

    private fun getDefaultLogLevel(): Int {
        val payload = executeUnary("GetDefaultLogLevel")
        val reader = ProtoReader(payload)
        var level = SingBoxLogLevelInfo
        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            if (tag == 1 && wireType == 0) {
                level = reader.readVarint().toInt()
            } else {
                reader.skipField(wireType)
            }
        }
        return level
    }

    private fun startStatusStream(scope: CoroutineScope) {
        val writer = ProtoWriter()
        writer.writeInt64(1, StatusIntervalNanos)
        startStream(scope, "SubscribeStatus", writer.toByteArray()) { payload ->
            runCatching {
                val status = decodeStatusMessage(payload)
                listener.onStatus(status)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to decode status message: ${error.message}", error)
            }
        }
    }

    private fun startGroupsStream(scope: CoroutineScope) {
        startStream(scope, "SubscribeGroups", ByteArray(0)) { payload ->
            runCatching {
                val proxiesState = decodeGroupsMessage(payload)
                listener.onProxies(proxiesState)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to decode groups message: ${error.message}", error)
            }
        }
    }

    private fun startConnectionsStream(scope: CoroutineScope) {
        val writer = ProtoWriter()
        writer.writeInt64(1, StatusIntervalNanos)
        startStream(scope, "SubscribeConnections", writer.toByteArray()) { payload ->
            runCatching {
                decodeAndDispatchConnections(payload)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to decode connections message: ${error.message}", error)
            }
        }
    }

    private fun startLogStream(scope: CoroutineScope) {
        startStream(scope, "SubscribeLog", ByteArray(0)) { payload ->
            runCatching {
                decodeAndDispatchLog(payload)
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to decode log message: ${error.message}", error)
            }
        }
    }

    private fun startStream(
        scope: CoroutineScope,
        method: String,
        requestPayload: ByteArray,
        onMessage: (ByteArray) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            var retryDelayMs = 500L
            while (isActive && !isClosed.get()) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("${target.control.baseUrl}/daemon.StartedService/$method")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 5000
                        readTimeout = 0 // Infinite read timeout for long-lived stream
                        setRequestProperty("Content-Type", GrpcWebContentType)
                        setRequestProperty("X-Grpc-Web", "1")
                        setRequestProperty("Accept", GrpcWebContentType)
                        if (target.control.secret.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer ${target.control.secret}")
                        }
                    }
                    conn = connection
                    activeConnections.add(connection)

                    val requestFrame = buildGrpcFrame(requestPayload)
                    connection.outputStream.use { os ->
                        os.write(requestFrame)
                        os.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        val err = runCatching { connection.errorStream?.readBytes()?.decodeToString() }.getOrNull()
                        throw IOException("HTTP $responseCode ${connection.responseMessage}: $err")
                    }

                    retryDelayMs = 500L
                    connection.inputStream.use { input ->
                        while (isActive && !isClosed.get()) {
                            val frame = input.readGrpcFrame() ?: break
                            if (frame.isTrailer) {
                                checkTrailerStatus(frame.payload)
                                break
                            } else {
                                onMessage(frame.payload)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    AndroidAppLogger.debug(LogTag, "Stream $method disconnected: ${e.message}")
                } finally {
                    conn?.let {
                        activeConnections.remove(it)
                        runCatching { it.disconnect() }
                    }
                }
                if (isActive && !isClosed.get()) {
                    delay(retryDelayMs.milliseconds)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(3000L)
                }
            }
        }
    }

    private fun startHealthCheck(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive && !isClosed.get()) {
                delay(3000L.milliseconds)
                if (!isActive || isClosed.get()) break
                val probeResult = runCatching { serviceStartedAtMillis() }
                if (probeResult.isSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                    if (consecutiveFailures >= 3) {
                        handleStreamError(probeResult.exceptionOrNull() ?: IOException("sing-box API health check failed"))
                        break
                    }
                }
            }
        }
    }

    private fun handleStreamError(e: Throwable) {
        if (hasDisconnected.compareAndSet(false, true)) {
            listener.onDisconnected(e.message ?: "Connection closed")
        }
    }

    private fun executeUnary(method: String, requestPayload: ByteArray = ByteArray(0)): ByteArray {
        val looper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (looper != null && Looper.myLooper() == looper) {
            return try {
                CompletableFuture.supplyAsync(
                    { executeUnaryDirect(method, requestPayload) },
                    Dispatchers.IO.asExecutor(),
                ).get(10, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        return executeUnaryDirect(method, requestPayload)
    }

    private fun executeUnaryDirect(method: String, requestPayload: ByteArray): ByteArray {
        val url = URL("${target.control.baseUrl}/daemon.StartedService/$method")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("Content-Type", GrpcWebContentType)
            setRequestProperty("X-Grpc-Web", "1")
            setRequestProperty("Accept", GrpcWebContentType)
            setRequestProperty("Connection", "close")
            if (target.control.secret.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${target.control.secret}")
            }
        }
        try {
            val frame = buildGrpcFrame(requestPayload)
            connection.outputStream.use { os ->
                os.write(frame)
                os.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.readBytes()?.decodeToString() }.getOrNull()
                throw IOException("HTTP $responseCode ${connection.responseMessage}: $errorBody")
            }
            val grpcStatusHeader = connection.getHeaderField("grpc-status")
            if (grpcStatusHeader != null && grpcStatusHeader.trim() != "0") {
                val rawMsg = connection.getHeaderField("grpc-message").orEmpty()
                val grpcMessage = runCatching { URLDecoder.decode(rawMsg, "UTF-8") }.getOrDefault(rawMsg)
                error("gRPC error $grpcStatusHeader: $grpcMessage")
            }
            var dataPayload: ByteArray? = null
            connection.inputStream.use { input ->
                while (true) {
                    val frame = input.readGrpcFrame() ?: break
                    if (frame.isTrailer) {
                        checkTrailerStatus(frame.payload)
                        break
                    } else {
                        dataPayload = frame.payload
                    }
                }
            }
            return dataPayload ?: ByteArray(0)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeStatusMessage(payload: ByteArray): SingBoxStatusMessage {
        val reader = ProtoReader(payload)
        var memory = 0L
        var trafficAvailable = false
        var uplink = 0L
        var downlink = 0L
        var uplinkTotal = 0L
        var downlinkTotal = 0L
        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            when (tag) {
                1 -> memory = reader.readVarint()
                5 -> trafficAvailable = reader.readVarint() != 0L
                6 -> uplink = reader.readVarint()
                7 -> downlink = reader.readVarint()
                8 -> uplinkTotal = reader.readVarint()
                9 -> downlinkTotal = reader.readVarint()
                else -> reader.skipField(wireType)
            }
        }
        return SingBoxStatusMessage(
            memory = memory,
            uplink = uplink,
            downlink = downlink,
            uplinkTotal = uplinkTotal,
            downlinkTotal = downlinkTotal,
            trafficAvailable = trafficAvailable,
        )
    }

    private fun decodeGroupsMessage(payload: ByteArray): SingBoxProxiesState {
        val rootReader = ProtoReader(payload)
        val groups = mutableListOf<SingBoxProxyGroup>()
        val nodes = linkedMapOf<String, SingBoxProxyNode>()

        while (rootReader.hasNext) {
            val (tag, wireType) = rootReader.readTag() ?: break
            if (tag == 1 && wireType == 2) {
                val groupReader = rootReader.readSubReader()
                var groupTag = ""
                var groupType = ""
                var selected = ""
                val itemNames = mutableListOf<String>()

                while (groupReader.hasNext) {
                    val (gTag, gWireType) = groupReader.readTag() ?: break
                    when (gTag) {
                        1 -> groupTag = groupReader.readString()
                        2 -> groupType = groupReader.readString()
                        4 -> selected = groupReader.readString()
                        6 -> {
                            val itemReader = groupReader.readSubReader()
                            var itemTag = ""
                            var itemType = ""
                            var urlTestTime = 0L
                            var urlTestDelay = 0

                            while (itemReader.hasNext) {
                                val (iTag, iWireType) = itemReader.readTag() ?: break
                                when (iTag) {
                                    1 -> itemTag = itemReader.readString()
                                    2 -> itemType = itemReader.readString()
                                    3 -> urlTestTime = itemReader.readVarint()
                                    4 -> urlTestDelay = itemReader.readVarint().toInt()
                                    else -> itemReader.skipField(iWireType)
                                }
                            }
                            if (itemTag.isNotEmpty()) {
                                itemNames += itemTag
                                nodes[itemTag] = singBoxProxyNode(
                                    name = itemTag,
                                    type = itemType,
                                    urlTestDelay = urlTestDelay,
                                    urlTestTime = urlTestTime,
                                )
                            }
                        }
                        else -> groupReader.skipField(gWireType)
                    }
                }

                if (groupTag.isNotEmpty()) {
                    groups += SingBoxProxyGroup(
                        name = groupTag,
                        type = groupType,
                        now = selected,
                        all = itemNames,
                    )
                }
            } else {
                rootReader.skipField(wireType)
            }
        }

        return SingBoxProxiesState(
            groups = groups,
            nodes = nodes.values.toList(),
            nodeByName = nodes,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun decodeAndDispatchConnections(payload: ByteArray) {
        val rootReader = ProtoReader(payload)
        var reset = false
        val events = mutableListOf<ParsedConnectionEvent>()

        while (rootReader.hasNext) {
            val (tag, wireType) = rootReader.readTag() ?: break
            when (tag) {
                1 -> {
                    val eventReader = rootReader.readSubReader()
                    var type = 0
                    var id = ""
                    var conn: ParsedConnection? = null
                    var uplinkDelta = 0L
                    var downlinkDelta = 0L
                    var closedAt = 0L

                    while (eventReader.hasNext) {
                        val (eTag, eWire) = eventReader.readTag() ?: break
                        when (eTag) {
                            1 -> type = eventReader.readVarint().toInt()
                            2 -> id = eventReader.readString()
                            3 -> conn = decodeConnection(eventReader.readSubReader())
                            4 -> uplinkDelta = eventReader.readVarint()
                            5 -> downlinkDelta = eventReader.readVarint()
                            6 -> closedAt = eventReader.readVarint()
                            else -> eventReader.skipField(eWire)
                        }
                    }
                    events += ParsedConnectionEvent(type, id, conn, uplinkDelta, downlinkDelta, closedAt)
                }
                2 -> reset = rootReader.readVarint() != 0L
                else -> rootReader.skipField(wireType)
            }
        }

        val snapshot = synchronized(connectionsLock) {
            if (reset) {
                connectionMap.clear()
            }
            for (event in events) {
                when (event.type) {
                    0 -> { // CONNECTION_EVENT_NEW
                        val c = event.connection
                        if (c != null) {
                            connectionMap[event.id] = MutableConnection(
                                id = c.id,
                                network = c.network,
                                inboundType = c.inboundType,
                                source = c.source,
                                destination = c.destination,
                                domain = c.domain,
                                process = c.process,
                                processPath = c.processPath,
                                uid = c.uid,
                                outbound = c.outbound,
                                outboundType = c.outboundType,
                                chains = c.chains,
                                rule = c.rule,
                                uplink = c.uplink,
                                downlink = c.downlink,
                                uplinkTotal = c.uplinkTotal,
                                downlinkTotal = c.downlinkTotal,
                                createdAt = c.createdAt,
                                closedAt = c.closedAt,
                            )
                        }
                    }
                    1 -> { // CONNECTION_EVENT_UPDATE
                        connectionMap[event.id]?.let { existing ->
                            existing.uplink = event.uplinkDelta
                            existing.downlink = event.downlinkDelta
                            existing.uplinkTotal += event.uplinkDelta
                            existing.downlinkTotal += event.downlinkDelta
                        }
                    }
                    2 -> { // CONNECTION_EVENT_CLOSED
                        connectionMap.remove(event.id)
                    }
                }
            }

            var uploadTotal = 0L
            var downloadTotal = 0L
            val values = mutableListOf<SingBoxConnection>()
            for (conn in connectionMap.values) {
                if (conn.closedAt != 0L) continue
                uploadTotal += conn.uplinkTotal
                downloadTotal += conn.downlinkTotal
                values += SingBoxConnection(
                    id = conn.id,
                    network = conn.network.lowercase(),
                    inboundType = conn.inboundType,
                    sourceAddress = conn.source,
                    destinationAddress = formatDisplayDestination(conn.destination, conn.domain),
                    process = conn.process,
                    processPath = conn.processPath,
                    uid = conn.uid,
                    outbound = conn.outbound,
                    outboundType = conn.outboundType,
                    chains = conn.chains,
                    rule = conn.rule,
                    uploadBytes = conn.uplinkTotal,
                    downloadBytes = conn.downlinkTotal,
                    uploadBytesPerSecond = conn.uplink,
                    downloadBytesPerSecond = conn.downlink,
                    startedAtMillis = conn.createdAt.takeIf { it > 0L },
                )
            }
            SingBoxConnectionsState(
                uploadTotalBytes = uploadTotal,
                downloadTotalBytes = downloadTotal,
                connections = values,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        listener.onConnections(snapshot)
    }

    private fun decodeConnection(reader: ProtoReader): ParsedConnection {
        var id = ""
        var inboundType = ""
        var network = ""
        var source = ""
        var destination = ""
        var domain = ""
        var createdAt = 0L
        var closedAt = 0L
        var uplink = 0L
        var downlink = 0L
        var uplinkTotal = 0L
        var downlinkTotal = 0L
        var rule = ""
        var outbound = ""
        var outboundType = ""
        val chains = mutableListOf<String>()
        var process = ""
        var processPath = ""
        var uid: Long? = null

        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            when (tag) {
                1 -> id = reader.readString()
                2 -> reader.readString() // inboundTag
                3 -> inboundType = reader.readString()
                4 -> reader.readVarint() // ipVersion
                5 -> network = reader.readString()
                6 -> source = reader.readString()
                7 -> destination = reader.readString()
                8 -> domain = reader.readString()
                9 -> reader.readString() // protocol
                12 -> createdAt = reader.readVarint()
                13 -> closedAt = reader.readVarint()
                14 -> uplink = reader.readVarint()
                15 -> downlink = reader.readVarint()
                16 -> uplinkTotal = reader.readVarint()
                17 -> downlinkTotal = reader.readVarint()
                18 -> rule = reader.readString()
                19 -> outbound = reader.readString()
                20 -> outboundType = reader.readString()
                21 -> chains += reader.readString()
                22 -> {
                    val procReader = reader.readSubReader()
                    val pkgNames = mutableListOf<String>()
                    var userName = ""
                    while (procReader.hasNext) {
                        val (pTag, pWireType) = procReader.readTag() ?: break
                        when (pTag) {
                            2 -> {
                                val u = procReader.readVarint()
                                if (u >= 0L) uid = u
                            }
                            3 -> userName = procReader.readString()
                            4 -> processPath = procReader.readString()
                            5 -> pkgNames += procReader.readString()
                            else -> procReader.skipField(pWireType)
                        }
                    }
                    process = pkgNames.firstOrNull() ?: userName
                }
                else -> reader.skipField(wireType)
            }
        }
        return ParsedConnection(
            id = id,
            inboundType = inboundType,
            network = network,
            source = source,
            destination = destination,
            domain = domain,
            createdAt = createdAt,
            closedAt = closedAt,
            uplink = uplink,
            downlink = downlink,
            uplinkTotal = uplinkTotal,
            downlinkTotal = downlinkTotal,
            rule = rule,
            outbound = outbound,
            outboundType = outboundType,
            chains = chains,
            process = process,
            processPath = processPath,
            uid = uid,
        )
    }

    private fun decodeAndDispatchLog(payload: ByteArray) {
        val reader = ProtoReader(payload)
        var reset = false
        val entries = mutableListOf<Pair<Int, String>>()
        while (reader.hasNext) {
            val (tag, wireType) = reader.readTag() ?: break
            when (tag) {
                1 -> {
                    val sub = reader.readSubReader()
                    var level = SingBoxLogLevelInfo
                    var msg = ""
                    while (sub.hasNext) {
                        val (sTag, sWire) = sub.readTag() ?: break
                        when (sTag) {
                            1 -> level = sub.readVarint().toInt()
                            2 -> msg = sub.readString()
                            else -> sub.skipField(sWire)
                        }
                    }
                    entries += level to msg
                }
                2 -> reset = reader.readVarint() != 0L
                else -> reader.skipField(wireType)
            }
        }
        if (reset) {
            logWriter.clear()
        }
        val toAppend = if (entries.size > 200) entries.takeLast(200) else entries
        for ((lvl, msg) in toAppend) {
            logWriter.append(level = lvl, message = msg, time = currentLogTime())
        }
    }

    private companion object {
        const val LogTag = "SingBoxCommandClient"
        const val GrpcWebContentType = "application/grpc-web+proto"
        const val StatusIntervalNanos = 1_000_000_000L
    }
}

private class ParsedConnectionEvent(
    val type: Int,
    val id: String,
    val connection: ParsedConnection?,
    val uplinkDelta: Long,
    val downlinkDelta: Long,
    val closedAt: Long,
)

private class ParsedConnection(
    val id: String,
    val inboundType: String,
    val network: String,
    val source: String,
    val destination: String,
    val domain: String,
    val createdAt: Long,
    val closedAt: Long,
    val uplink: Long,
    val downlink: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val rule: String,
    val outbound: String,
    val outboundType: String,
    val chains: List<String>,
    val process: String,
    val processPath: String,
    val uid: Long?,
)

private class MutableConnection(
    val id: String,
    val network: String,
    val inboundType: String,
    val source: String,
    val destination: String,
    val domain: String,
    val process: String,
    val processPath: String,
    val uid: Long?,
    val outbound: String,
    val outboundType: String,
    val chains: List<String>,
    val rule: String,
    var uplink: Long,
    var downlink: Long,
    var uplinkTotal: Long,
    var downlinkTotal: Long,
    val createdAt: Long,
    var closedAt: Long = 0L,
)

private fun formatDisplayDestination(destination: String, domain: String): String {
    if (domain.isBlank()) return destination
    val port = when {
        destination.contains("]:") -> destination.substringAfterLast("]:")
        destination.startsWith("[") -> ""
        destination.count { it == ':' } == 1 -> destination.substringAfterLast(':')
        else -> ""
    }
    return if (port.isNotEmpty()) "$domain:$port" else domain
}

private class GrpcFrame(val isTrailer: Boolean, val payload: ByteArray)

private fun InputStream.readExact(buffer: ByteArray, offset: Int, length: Int) {
    var read = 0
    while (read < length) {
        val count = this.read(buffer, offset + read, length - read)
        if (count < 0) throw EOFException("Unexpected EOF while reading gRPC-Web frame")
        read += count
    }
}

private fun InputStream.readGrpcFrame(): GrpcFrame? {
    val flag = read()
    if (flag < 0) return null
    val header = ByteArray(4)
    readExact(header, 0, 4)
    val length = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)
    val payload = ByteArray(length)
    readExact(payload, 0, length)
    val isTrailer = (flag and 0x80) != 0
    return GrpcFrame(isTrailer = isTrailer, payload = payload)
}

private fun buildGrpcFrame(payload: ByteArray): ByteArray {
    val frame = ByteArray(5 + payload.size)
    frame[0] = 0.toByte() // flags: uncompressed data
    frame[1] = (payload.size ushr 24).toByte()
    frame[2] = (payload.size ushr 16).toByte()
    frame[3] = (payload.size ushr 8).toByte()
    frame[4] = payload.size.toByte()
    System.arraycopy(payload, 0, frame, 5, payload.size)
    return frame
}

private fun checkTrailerStatus(trailerPayload: ByteArray) {
    val text = String(trailerPayload, Charsets.US_ASCII)
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("grpc-status:", ignoreCase = true)) {
            val status = trimmed.substringAfter(':').trim().toIntOrNull() ?: 0
            if (status != 0) {
                val msgLine = text.lineSequence().firstOrNull { it.trim().startsWith("grpc-message:", ignoreCase = true) }
                val rawMsg = msgLine?.substringAfter(':')?.trim().orEmpty()
                val msg = runCatching { URLDecoder.decode(rawMsg, "UTF-8") }.getOrDefault(rawMsg)
                error("gRPC error $status: $msg")
            }
        }
    }
}

internal class ProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun writeVarint(value: Long) {
        var v = value
        while ((v and 0x7F.inv()) != 0L) {
            buffer.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        buffer.write((v and 0x7F).toInt())
    }

    fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    fun writeInt32(fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(fieldNumber, 0)
        writeVarint(value.toLong())
    }

    fun writeInt64(fieldNumber: Int, value: Long) {
        if (value == 0L) return
        writeTag(fieldNumber, 0)
        writeVarint(value)
    }

    fun writeString(fieldNumber: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(fieldNumber, 2)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
    }

    fun writeMessage(fieldNumber: Int, writer: ProtoWriter) {
        val bytes = writer.toByteArray()
        if (bytes.isEmpty()) return
        writeTag(fieldNumber, 2)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

internal class ProtoReader(
    private val bytes: ByteArray,
    private var pos: Int = 0,
    private val limit: Int = bytes.size,
) {
    val hasNext: Boolean get() = pos < limit

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < limit) {
            val b = bytes[pos++].toLong()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80L) == 0L) return result
            shift += 7
            if (shift > 64) break
        }
        return result
    }

    fun readTag(): Pair<Int, Int>? {
        if (!hasNext) return null
        val tag = readVarint()
        if (tag <= 0L) return null
        val fieldNumber = (tag ushr 3).toInt()
        val wireType = (tag and 0x07).toInt()
        if (fieldNumber <= 0) return null
        return fieldNumber to wireType
    }

    fun readString(): String {
        val len = readVarint().toInt()
        if (len <= 0) return ""
        val safeLen = minOf(len, limit - pos)
        val s = String(bytes, pos, safeLen, Charsets.UTF_8)
        pos += safeLen
        return s
    }

    fun readSubReader(): ProtoReader {
        val len = readVarint().toInt()
        if (len <= 0) return ProtoReader(bytes, pos, pos)
        val end = minOf(pos + len, limit)
        val sub = ProtoReader(bytes, pos, end)
        pos = end
        return sub
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos = minOf(pos + 8, limit)
            2 -> {
                val len = readVarint().toInt()
                if (len > 0) pos = minOf(pos + len, limit)
            }
            5 -> pos = minOf(pos + 4, limit)
            else -> pos = limit
        }
    }
}

internal fun singBoxProxyNode(
    name: String,
    type: String,
    udp: Boolean = false,
    urlTestDelay: Int,
    urlTestTime: Long,
): SingBoxProxyNode = SingBoxProxyNode(
    name = name,
    type = type,
    udp = udp,
    delay = urlTestDelay.takeIf { delay -> delay > 0 },
    delayUpdatedAtEpochSeconds = urlTestTime.takeIf { time -> time > 0L },
)

internal class SingBoxCommandLogWriter(
    private val appendPersisted: (level: String, message: String, time: String) -> Unit =
        AndroidCoreLogRepository::appendPersisted,
    private val clearPersisted: () -> Unit = AndroidCoreLogRepository::clear,
) {
    private var defaultLogLevel = SingBoxLogLevelInfo

    fun setDefaultLogLevel(level: Int) {
        defaultLogLevel = level.takeIf { it in SingBoxLogLevelPanic..SingBoxLogLevelTrace }
            ?: SingBoxLogLevelInfo
    }

    fun append(level: Int, message: String, time: String = currentLogTime()) {
        if (level !in SingBoxLogLevelPanic..defaultLogLevel) return
        appendPersisted(level.toLogLevelName(), message, time)
    }

    fun clear() {
        clearPersisted()
    }
}

private fun Int.toLogLevelName(): String = when (this) {
    0 -> "panic"
    1 -> "fatal"
    2 -> "error"
    3 -> "warn"
    4 -> "info"
    5 -> "debug"
    6 -> "trace"
    else -> "info"
}

private const val SingBoxLogLevelPanic = 0
private const val SingBoxLogLevelInfo = 4
private const val SingBoxLogLevelTrace = 6

private fun String.toOfficialClashMode(): String = when (lowercase()) {
    "global" -> "Global"
    "direct" -> "Direct"
    else -> "Rule"
}

package xyz.astolfo.astolfocommunity

import io.sentry.Sentry
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import okhttp3.*
import okio.ByteString
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class AstolfoWebSocketClient(val wsName: String, private val requestBuilder: Request.Builder, val listener: WebSocketListener) {

    companion object {
        private val websocketContext = newFixedThreadPoolContext(5, "Astolfo WebSocket Client")
        private val logger = LoggerFactory.getLogger(AstolfoWebSocketClient::class.java)
        val ASTOLFO_WS_CLIENT = OkHttpClient.Builder()
                .pingInterval(2, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build()
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logger.info("[$wsName WS] Connected!")
            failedConnects = 0
            runBlocking(websocketContext) {
                messagesToSendMutex.withLock {
                    val messagesSent = mutableListOf<String>()
                    for (message in messagesToSend) {
                        if(!webSocket.send(message)) break
                        messagesSent.add(message)
                    }
                    messagesToSend.removeAll(messagesSent)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logger.info("[$wsName WS] Socket Closed!")
            listener.onClosed(webSocket, code, reason)
            connected = false
            wsActor.sendBlocking(Closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logger.info("[$wsName WS] Socket Failure!")
            listener.onFailure(webSocket, t, response)
            failedConnects++
            connected = false
            wsActor.sendBlocking(Closed)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)
    }

    private lateinit var ws: WebSocket
    private var connected = false
    private var requestedConnected = false
    private var failedConnects = 0

    private var messagesToSend = mutableListOf<String>()
    private var messagesToSendMutex = Mutex()

    private var destroyed = false

    private interface WSEvent
    private object Connect : WSEvent
    private object Disconnect : WSEvent
    private object Reconnect : WSEvent
    private object Closed : WSEvent

    private val wsActor = actor<WSEvent>(context = websocketContext, capacity = Channel.UNLIMITED, start = CoroutineStart.LAZY) {
        for (event in channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        logger.info("[$wsName WS] Stopping...")
        handleEvent(Disconnect)
    }

    private suspend fun handleEvent(event: WSEvent) {
        when (event) {
            is Connect -> {
                logger.info("[$wsName WS] Connecting...")
                requestedConnected = true
                if (connected) return // Ignore if already connected
                try {
                    val wsRequest = requestBuilder.build()
                    ws = ASTOLFO_WS_CLIENT.newWebSocket(wsRequest, wsListener)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    Sentry.capture(e)
                    connected = false
                    handleEvent(Reconnect)
                }
            }
            is Disconnect -> {
                logger.info("[$wsName WS] Disconnecting...")
                requestedConnected = false
                if (!connected) return
                connected = false
                ws.close(1000, "Normal Disconnect")
            }
            is Closed -> {
                logger.info("[$wsName WS] Closed!")
                if (connected) return // Ignore if connected
                if (!requestedConnected) return // Ignore if close state is requested
                handleEvent(Reconnect)
            }
            is Reconnect -> {
                val seconds = (30L * failedConnects).coerceIn(30, 5 * 60)
                logger.info("[$wsName WS] Reconnecting in $seconds seconds...")
                if (connected) handleEvent(Disconnect)
                connected = false // Just to make sure
                requestedConnected = true
                launch(context = websocketContext) {
                    delay(seconds, TimeUnit.SECONDS) // wait 30 seconds before reconnecting
                    if (requestedConnected) wsActor.send(Connect)
                }
            }
        }
    }

    suspend fun send(message: String) = messagesToSendMutex.withLock {
        if (messagesToSend.isNotEmpty() || !connected || !ws.send(message))
            messagesToSend.add(message)
    }

    fun startBlocking() = runBlocking(websocketContext) {
        start()
    }

    suspend fun start() {
        wsActor.send(Connect)
    }

    fun dispose() {
        destroyed = true
        wsActor.close()
    }


}
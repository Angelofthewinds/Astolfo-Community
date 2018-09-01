package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.*
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class CommandSessionImpl(override val commandPath: String) : CommandSession {

    private val listeners = CopyOnWriteArrayList<ResponseListenerEntry>()
    val parentJob = Job()

    private var destroyed = false

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        if (destroyed) IllegalStateException("You cannot register an updater to a destroyed session!")
        launch(parent = parentJob) {
            while (isActive) {
                updater.invoke(this@CommandSessionImpl)
                delay(rate, unit)
            }
        }
    }

    override fun responseListener(listener: ResponseListener): DisposableHandle {
        if (destroyed) IllegalStateException("You cannot register an listener to a destroyed session!")
        val entry = ResponseListenerEntry(listener)
        listeners += entry
        return entry.handle
    }

    override suspend fun onMessageReceived(commandScope: CommandScope): Boolean {
        var topShouldRunCommand = true
        listeners.forEach {
            it.listener(object : CommandResponseScope, CommandScope by commandScope, DisposableHandle by it.handle {
                override var shouldRunCommand
                    get() = topShouldRunCommand
                    set(value) {
                        topShouldRunCommand = value
                    }
            })
        }
        return topShouldRunCommand
    }

    override fun destroy() = runBlocking {
        destroyed = true
        parentJob.cancelAndJoin()
    }

    inner class ResponseListenerEntry(val listener: ResponseListener) {
        val handle = object : DisposableHandle {
            override fun dispose() {
                listeners -= this@ResponseListenerEntry
            }
        }
    }
}

class InheritedCommandSession(override val commandPath: String) : CommandSession {

    private fun inheritedError(): NotImplementedError = throw NotImplementedError("Inherited Actions don't support command sessions!")

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        throw inheritedError()
    }

    override fun responseListener(listener: ResponseListener): DisposableHandle = NonDisposableHandle

    override suspend fun onMessageReceived(commandScope: CommandScope): Boolean = true

    override fun destroy() {
        throw inheritedError()
    }

}

interface CommandResponseScope : CommandScope, DisposableHandle {
    var shouldRunCommand: Boolean

    fun dispose(shouldRunCommand: Boolean) {
        dispose()
        this.shouldRunCommand = shouldRunCommand
    }
}

typealias ResponseListener = suspend CommandResponseScope.() -> Unit

interface CommandSession {
    val commandPath: String

    fun responseListener(listener: ResponseListener): DisposableHandle

    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit)

    /**
     * @return true if command should run
     */
    suspend fun onMessageReceived(commandScope: CommandScope): Boolean

    fun destroy()
}
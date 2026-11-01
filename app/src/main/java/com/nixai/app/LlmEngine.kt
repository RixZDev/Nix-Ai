package com.nixai.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatMsg(val role: String, val text: String)

/** Envuelve LiteRT-LM: carga el modelo una vez y genera respuestas en streaming. */
class LlmEngine(private val ctx: Context, private val scope: CoroutineScope) {

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var loading = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    private val loadMutex = Mutex()
    private var genJob: Job? = null

    val isLoaded: Boolean get() = engine != null

    fun loadAsync(path: String) {
        if (engine != null || loading) return
        loading = true
        loadError = null
        scope.launch(Dispatchers.Default) {
            try {
                load(path)
            } catch (t: Throwable) {
                loadError = "No se pudo iniciar el modelo: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun load(path: String) = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            if (engine != null) return@withLock
            val cfg = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = ctx.cacheDir.path
            )
            val e = Engine(cfg)
            e.initialize()
            engine = e
        }
    }

    /** Recorta el historial para que quepa en la ventana de contexto del modelo (4096 tokens). */
    private fun trim(history: List<ChatMsg>): List<ChatMsg> {
        val budget = 6500 // caracteres aprox.
        var total = 0
        val out = ArrayDeque<ChatMsg>()
        for (m in history.asReversed()) {
            val len = m.text.length
            if (out.isNotEmpty() && total + len > budget) break
            out.addFirst(m)
            total += len
        }
        // Debe empezar con un mensaje del usuario
        while (out.size > 1 && out.first().role != "user") out.removeFirst()
        return out.toList()
    }

    fun generate(
        history: List<ChatMsg>,
        system: String,
        temperature: Double,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        genJob?.cancel()
        genJob = scope.launch(Dispatchers.Default) {
            try {
                val e = engine ?: throw IllegalStateException("El modelo todavía se está preparando.")
                val msgs = trim(history)
                if (msgs.isEmpty() || msgs.last().role != "user") throw IllegalStateException("Mensaje vacío.")
                val prior = msgs.dropLast(1).map {
                    if (it.role == "user") Message.user(it.text) else Message.model(it.text)
                }
                val conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        initialMessages = prior,
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature)
                    )
                )
                conv.use { c ->
                    var acc = ""
                    c.sendMessageAsync(msgs.last().text).collect { m ->
                        val t = m.toString()
                        // Sirve tanto si el flujo entrega fragmentos nuevos como el texto acumulado
                        if (acc.isNotEmpty() && t.length > acc.length && t.startsWith(acc)) {
                            onChunk(t.substring(acc.length))
                            acc = t
                        } else {
                            onChunk(t)
                            acc += t
                        }
                    }
                }
                onDone()
            } catch (ce: CancellationException) {
                onDone()
                throw ce
            } catch (t: Throwable) {
                onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        genJob?.cancel()
    }

    fun close() {
        genJob?.cancel()
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
    }
}

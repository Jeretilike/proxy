package com.chesslens.android.engine

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EngineAnalysis(
    /** Best move in UCI notation, e.g. "g1f3". */
    val bestMove: String,
    /** Centipawns from the side to move's point of view. */
    val centipawns: Int?,
    /** Mate in N from the side to move's point of view (negative: getting mated). */
    val mate: Int?,
    val depth: Int?,
    val principalVariation: List<String>,
)

class EngineException(message: String) : Exception(message)

/**
 * Runs Stockfish 17 as a separate process and talks UCI over stdin/stdout. The executable is
 * built for arm64 by CI (with its neural networks embedded) and shipped as `libstockfish.so`, so
 * Android installs it into the app's native library folder, from where it can be executed.
 */
class StockfishEngine(private val context: Context) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val lines = LinkedBlockingQueue<String>()
    private val mutex = Mutex()

    private val executable: File get() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    val isBundled: Boolean get() = executable.exists()

    /** Starts the engine once; later calls return immediately. */
    suspend fun start() = mutex.withLock { withContext(Dispatchers.IO) { ensureStarted() } }

    suspend fun analyze(fen: String, movetimeMillis: Int): EngineAnalysis = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureStarted()
            lines.clear()
            send("position fen $fen")
            send("go movetime $movetimeMillis")
            var last: Map<String, String>? = null
            var pv: List<String> = emptyList()
            val deadline = System.currentTimeMillis() + movetimeMillis + 15_000
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    send("stop")
                    throw EngineException("Stockfish did not answer in time.")
                }
                val line = lines.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
                // Keep the main line's latest report (other info lines carry no pv).
                val mainLine = " multipv " !in line || " multipv 1 " in line
                if (line.startsWith("info ") && " pv " in line && mainLine) {
                    last = parseInfo(line)
                    pv = line.substringAfter(" pv ").trim().split(" ")
                }
                if (line.startsWith("bestmove")) {
                    val move = line.split(" ").getOrNull(1) ?: ""
                    if (move.isEmpty() || move == "(none)" || move == "0000") {
                        throw EngineException("No legal moves: the game is over (checkmate or stalemate).")
                    }
                    return@withContext EngineAnalysis(
                        bestMove = move,
                        centipawns = last?.get("cp")?.toIntOrNull(),
                        mate = last?.get("mate")?.toIntOrNull(),
                        depth = last?.get("depth")?.toIntOrNull(),
                        principalVariation = pv.ifEmpty { listOf(move) },
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw EngineException("unreachable")
        }
    }

    private fun parseInfo(line: String): Map<String, String> {
        val tokens = line.split(" ")
        val result = HashMap<String, String>()
        var i = 0
        while (i < tokens.size - 1) {
            when (tokens[i]) {
                "depth" -> result["depth"] = tokens[i + 1]
                "cp" -> result["cp"] = tokens[i + 1]
                "mate" -> result["mate"] = tokens[i + 1]
            }
            i++
        }
        return result
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        if (!isBundled) throw EngineException("Stockfish is missing from this build of the app.")
        val p = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.put(line)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "stockfish-reader" }.start()

        send("uci")
        waitFor("uciok", 20_000)
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
        send("isready")
        waitFor("readyok", 30_000)
    }

    private fun waitFor(token: String, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw EngineException("Stockfish didn't start in time. It will try again on the next move.")
            val line = lines.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (line.startsWith(token)) return
        }
    }

    private fun send(command: String) {
        val w = writer ?: throw EngineException("Stockfish is not running.")
        w.write(command + "\n")
        w.flush()
    }

    fun shutdown() {
        try { send("quit") } catch (_: Exception) {}
        process?.destroy()
        process = null
    }
}

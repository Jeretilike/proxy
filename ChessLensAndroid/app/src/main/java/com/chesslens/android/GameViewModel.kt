package com.chesslens.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.Piece
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.PieceKind
import com.chesslens.android.chess.Square
import com.chesslens.android.chess.UciMove
import com.chesslens.android.engine.EngineAnalysis
import com.chesslens.android.engine.StockfishEngine
import com.chesslens.android.glasses.GlassesManager
import com.chesslens.android.vision.BoardCorners
import com.chesslens.android.vision.BoardTracker
import com.chesslens.android.vision.Frame
import com.chesslens.android.vision.MoveDetection
import com.chesslens.android.vision.MoveDetector
import com.chesslens.android.vision.SceneChangeDetector
import com.chesslens.android.vision.TrackerResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class TrackingState {
    object NotCalibrated : TrackingState()
    object Watching : TrackingState()
    object Thinking : TrackingState()
    object Lost : TrackingState()
    data class Unsure(val message: String) : TrackingState()
    data class GameOver(val result: String) : TrackingState()
}

class CalibrationDraft(val frame: Frame, val bitmap: Bitmap, val corners: BoardCorners)

/**
 * Runs a game in one of two modes:
 * - Glasses: finds the physical board by itself, then checks it whenever the view settles and at
 *   least every few seconds; on the wearer's turn Stockfish's best move is spoken.
 * - Screen: both sides' moves are tapped on the on-screen board and the best move is spoken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    val glasses = GlassesManager(viewModelScope)
    private val engine = StockfishEngine(app)
    private val speaker = Speaker(app)
    private val tracker = BoardTracker()
    /** All tracker work runs on this one background thread. */
    private val vision = Dispatchers.Default.limitedParallelism(1)

    var mode by mutableStateOf(settings.playMode); private set
    var state by mutableStateOf<TrackingState>(TrackingState.NotCalibrated); private set
    var position by mutableStateOf(ChessPosition.START); private set
    val history = mutableStateListOf<ChessPosition>()
    val moveLog = mutableStateListOf<String>()
    var suggestion by mutableStateOf<EngineAnalysis?>(null); private set
    var lastEvent by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Square?>(null); private set
    var corners by mutableStateOf<BoardCorners?>(null); private set
    var searchOutline by mutableStateOf<BoardCorners?>(null); private set
    var mismatched by mutableStateOf<List<Square>>(emptyList()); private set
    var calibration by mutableStateOf<CalibrationDraft?>(null); private set
    var playerColor by mutableStateOf(settings.playerColor); private set

    val isCalibrated: Boolean get() = corners != null
    val isPlayersTurn: Boolean get() = position.sideToMove == playerColor
    private val idleState: TrackingState get() = if (mode == PlayMode.SCREEN || isCalibrated) TrackingState.Watching else TrackingState.NotCalibrated

    private val sceneDetector = SceneChangeDetector()
    private val lastFrameCheck = AtomicLong(0)
    private var lastCheckStarted = 0L
    private var isChecking = false
    private var isLocating = false
    private var lastLocateAttempt = 0L
    private var candidateCorners: BoardCorners? = null
    private var previousMismatches: Set<Int> = emptySet()
    private var lostCount = 0
    private var lastWarning = 0L
    private var engineJob: Job? = null

    init {
        glasses.onFrame = { frame -> onFrameBackground(frame) }
        viewModelScope.launch { try { engine.start() } catch (_: Exception) {} }
        if (mode == PlayMode.SCREEN) { state = TrackingState.Watching; positionChanged(null, speak = false) }
    }

    override fun onCleared() {
        engine.shutdown()
        speaker.shutdown()
    }

    val statusText: String
        get() = when (val s = state) {
            TrackingState.NotCalibrated ->
                if (glasses.isStreaming) "Looking for the board… Set up the position shown below and look at the whole board."
                else "Start the glasses camera to begin."
            TrackingState.Watching -> when {
                mode == PlayMode.SCREEN -> if (isPlayersTurn) "Your move: tap it on the board, or tap Play it." else "Enter your opponent's move on the board."
                isPlayersTurn -> "Your move."
                else -> "Watching for your opponent's move…"
            }
            TrackingState.Thinking -> "Stockfish is thinking…"
            TrackingState.Lost -> "Board not found. Look at the whole board."
            is TrackingState.Unsure -> s.message
            is TrackingState.GameOver -> s.result
        }

    fun suggestionText(): String? = suggestion?.let { UciMove.parse(it.bestMove) }?.let { position.board.shortNotation(it) }
    fun spokenSuggestion(): String? = suggestion?.let { UciMove.parse(it.bestMove) }?.let { position.board.spokenDescription(it) }

    fun evaluationText(): String? {
        val s = suggestion ?: return null
        val sign = if (position.sideToMove == PieceColor.WHITE) 1 else -1
        s.mate?.let { return "#${it * sign}" }
        val cp = s.centipawns ?: return null
        return String.format("%+.2f", cp * sign / 100.0)
    }

    fun lineText(): String? {
        val s = suggestion ?: return null
        var walker = position
        val parts = mutableListOf<String>()
        for (uci in s.principalVariation.take(8)) {
            val move = UciMove.parse(uci) ?: break
            parts += walker.board.shortNotation(move)
            walker = walker.applying(move)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    // Settings

    fun changeMode(newMode: PlayMode) {
        if (newMode == mode) return
        mode = newMode
        settings.playMode = newMode
        selected = null; candidateCorners = null; searchOutline = null
        state = idleState
        positionChanged(null, speak = false)
    }

    fun changePlayerColor(color: PieceColor) {
        settings.playerColor = color
        playerColor = color
        candidateCorners = null
        positionChanged(null, speak = false)
    }

    // Calibration by hand (fallback)

    fun beginCalibration() {
        val frame = glasses.latestFrame ?: run { state = TrackingState.Unsure("Start the glasses camera and look at the board first."); return }
        val bitmap = glasses.preview.value ?: return
        val board = position.board
        viewModelScope.launch {
            val guess = calibration?.corners ?: withContext(vision) { tracker.suggestCorners(frame, board) }
            calibration = CalibrationDraft(frame, bitmap, guess)
        }
    }

    fun moveCalibrationCorner(index: Int, x: Double, y: Double) {
        val d = calibration ?: return
        val pts = d.corners.points.toMutableList()
        pts[index] = com.chesslens.android.vision.Pt(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        calibration = CalibrationDraft(d.frame, d.bitmap, BoardCorners.of(pts))
    }

    fun cancelCalibration() { calibration = null }

    fun finishCalibration() {
        val d = calibration ?: return
        calibration = null
        val board = position.board
        viewModelScope.launch {
            val ok = withContext(vision) { tracker.calibrate(d.frame, d.corners, board) }
            if (!ok) { state = TrackingState.Unsure("Those corners didn't work. Try again."); return@launch }
            corners = d.corners
            sceneDetector.reset(); lostCount = 0
            positionChanged(null, speak = true)
        }
    }

    // Game control

    fun newGame() {
        engineJob?.cancel()
        position = ChessPosition.START
        history.clear(); moveLog.clear()
        suggestion = null; lastEvent = null; selected = null
        corners = null; candidateCorners = null; searchOutline = null
        state = idleState
        viewModelScope.launch(vision) { tracker.reset() }
        if (mode == PlayMode.SCREEN) positionChanged(null, speak = true)
    }

    fun undo() {
        if (history.isEmpty()) return
        position = history.removeAt(history.lastIndex)
        if (moveLog.isNotEmpty()) moveLog.removeAt(moveLog.lastIndex)
        lastEvent = "Took back the last move."
        resyncTracker()
        positionChanged(null, speak = false)
    }

    /** Tap a piece of the side to move, then its destination. */
    fun tap(square: Square) {
        val from = selected
        if (from != null) {
            selected = null
            val move = position.legalMoves().filter { it.from == from && it.to == square }
                .firstOrNull { it.promotion == null || it.promotion == PieceKind.QUEEN }
            if (move != null) {
                record(listOf(move), announceOpponent = false, speak = true)
                resyncTracker()
                return
            }
        }
        if (position.board[square]?.color == position.sideToMove) selected = square
    }

    fun playSuggestion() {
        val move = suggestion?.let { UciMove.parse(it.bestMove) } ?: return
        if (move in position.legalMoves()) record(listOf(move), announceOpponent = false, speak = true)
    }

    private fun replacePosition(newPosition: ChessPosition) {
        history.add(position); moveLog.add("(edit)")
        position = newPosition
        selected = null
        resyncTracker()
        positionChanged(null, speak = false)
    }

    fun setPiece(piece: Piece?, square: Square) = replacePosition(ChessPosition.of(position.board.with(square, piece), position.sideToMove))
    fun toggleSideToMove() = replacePosition(ChessPosition.of(position.board, position.sideToMove.opposite))

    fun applyFen(text: String) {
        val parsed = ChessPosition.fromFen(text) ?: run { state = TrackingState.Unsure("That FEN couldn't be read."); return }
        replacePosition(parsed)
    }

    fun speakAgain() { spokenSuggestion()?.let { speaker.say("Play $it") } }

    fun checkNow() {
        val frame = glasses.latestFrame ?: return
        if (!isChecking) viewModelScope.launch { check(frame) }
    }

    // Watching the board

    private fun onFrameBackground(frame: Frame) {
        val now = System.currentTimeMillis()
        val last = lastFrameCheck.get()
        if (now - last < 250 || !lastFrameCheck.compareAndSet(last, now)) return
        viewModelScope.launch { onFrame(frame, now) }
    }

    private fun onFrame(frame: Frame, now: Long) {
        if (mode != PlayMode.GLASSES || calibration != null || state is TrackingState.GameOver) return
        if (!isCalibrated) { locateIfDue(frame, now); return }
        if (!settings.handsFree || isChecking) return
        val settled = sceneDetector.shouldAnalyze(frame, now)
        val due = now - lastCheckStarted >= settings.checkIntervalSeconds * 1000L
        if (!settled && !due) return
        sceneDetector.markAnalyzed(frame)
        viewModelScope.launch { check(frame) }
    }

    private fun locateIfDue(frame: Frame, now: Long) {
        if (isLocating || now - lastLocateAttempt < 1000) return
        lastLocateAttempt = now
        isLocating = true
        val board = position.board
        viewModelScope.launch {
            try {
                val found = withContext(vision) { tracker.locateBoard(frame, board) }
                if (found == null) { candidateCorners = null; searchOutline = null; return@launch }
                val previous = candidateCorners
                // Two detections in a row must agree before tracking starts.
                if (previous == null || previous.distance(found.first) >= 0.04) {
                    candidateCorners = found.first; searchOutline = found.first
                    return@launch
                }
                candidateCorners = null; searchOutline = null
                if (isCalibrated || calibration != null || mode != PlayMode.GLASSES || position.board != board) return@launch
                if (!withContext(vision) { tracker.calibrate(frame, found.first, board) }) return@launch
                corners = found.first
                sceneDetector.reset(); lostCount = 0
                positionChanged("Board found", speak = true)
            } finally {
                isLocating = false
            }
        }
    }

    private class CheckResult(val detection: MoveDetection, val mismatches: Set<Int>, val features: Array<FloatArray>)

    private suspend fun check(frame: Frame) {
        isChecking = true
        lastCheckStarted = System.currentTimeMillis()
        try {
            val start = position
            val first = observe(frame, start) ?: return
            var found: Pair<List<UciMove>, ChessPosition>? = (first.detection as? MoveDetection.Moves)?.let { it.moves to it.result }
            if (found == null && first.mismatches.isNotEmpty()) found = recover(start, first.features)
            if (found == null) {
                reportMismatches(first.mismatches)
                handle(first.detection, first.mismatches)
                return
            }
            // Confirm with a second frame so one bad frame can't corrupt the game.
            delay(600)
            val secondFrame = glasses.latestFrame ?: return
            val second = observe(secondFrame, start) ?: return
            if (position != start) return
            val (moves, result) = found
            val confirmed = (second.detection as? MoveDetection.Moves)?.result?.board == result.board ||
                BoardTracker.mismatchedSquares(second.features, result.board, playerColor).isEmpty()
            if (!confirmed) { reportMismatches(second.mismatches); return }
            withContext(vision) { tracker.commitPending(result.board) }
            record(moves, announceOpponent = true, speak = true)
        } finally {
            isChecking = false
        }
    }

    private suspend fun observe(frame: Frame, start: ChessPosition): CheckResult? {
        val player = playerColor
        return when (val r = withContext(vision) { tracker.observe(frame, player) }) {
            TrackerResult.NotCalibrated -> null
            TrackerResult.Lost -> {
                lostCount++
                state = TrackingState.Lost
                if (lostCount == 4) warn("I lost the board. Look at the whole board.")
                null
            }
            is TrackerResult.Observed -> {
                lostCount = 0
                corners = r.corners
                if (state == TrackingState.Lost) state = idleState
                val detection = withContext(Dispatchers.Default) { MoveDetector.detect(start, r.observation, player) }
                CheckResult(detection, r.mismatches, r.features)
            }
        }
    }

    private suspend fun recover(start: ChessPosition, features: Array<FloatArray>): Pair<List<UciMove>, ChessPosition>? {
        val player = playerColor
        return withContext(Dispatchers.Default) {
            MoveDetector.recover(start) { board -> BoardTracker.mismatchedSquares(features, board, player).size }
        }
    }

    /** Squares that look wrong in two checks in a row are shown (and spoken once). */
    private fun reportMismatches(current: Set<Int>) {
        val persistent = current.intersect(previousMismatches)
        previousMismatches = current
        val squares = persistent.sorted().map { Square.fromIndex(it) }
        if (squares == mismatched) return
        mismatched = squares
        if (squares.isNotEmpty()) warn("The board doesn't match the game. Check your phone.")
    }

    private fun handle(detection: MoveDetection, mismatches: Set<Int>) {
        when (detection) {
            MoveDetection.NoChange -> if (mismatches.isEmpty()) {
                // Refresh the reference only when every square agrees, so a missed move can't be baked in.
                val board = position.board
                viewModelScope.launch(vision) { tracker.commitPending(board) }
                if (state is TrackingState.Unsure) state = idleState
            }
            MoveDetection.Unclear -> state = TrackingState.Unsure("Something changed but it doesn't match a move. If a move was played, tap it on the board.")
            MoveDetection.Ambiguous -> {
                state = TrackingState.Unsure("Not sure which move was played. Tap it on the board.")
                warn("I didn't catch that move.")
            }
            is MoveDetection.Moves -> Unit
        }
    }

    // Moves and suggestions

    private fun record(moves: List<UciMove>, announceOpponent: Boolean, speak: Boolean) {
        var walker = position
        val opponentMoves = mutableListOf<String>()
        for (move in moves) {
            moveLog.add(walker.board.shortNotation(move))
            if (walker.sideToMove != playerColor) opponentMoves += walker.board.spokenDescription(move)
            history.add(walker)
            walker = walker.applying(move)
        }
        position = walker
        selected = null
        lastEvent = "Played " + moveLog.takeLast(moves.size).joinToString(", ")
        val announcement = if (announceOpponent && settings.announceOpponent && opponentMoves.isNotEmpty())
            "Opponent: " + opponentMoves.joinToString(", then ") else null
        positionChanged(announcement, speak)
    }

    private fun positionChanged(announcement: String?, speak: Boolean) {
        engineJob?.cancel()
        suggestion = null
        previousMismatches = emptySet(); mismatched = emptyList()

        if (position.legalMoves().isEmpty()) {
            val result = if (position.board.isInCheck(position.sideToMove)) "Checkmate." else "Stalemate."
            state = TrackingState.GameOver(result)
            if (speak) speaker.say(listOfNotNull(announcement, result).joinToString(". "))
            return
        }
        if (!isPlayersTurn) {
            state = idleState
            if (speak && announcement != null) speaker.say(announcement)
            return
        }
        val issues = position.board.validationIssues(position.sideToMove)
        if (issues.isNotEmpty()) { state = TrackingState.Unsure("The position looks impossible: " + issues.joinToString(" ")); return }

        state = TrackingState.Thinking
        val analyzed = position
        engineJob = viewModelScope.launch {
            try {
                val analysis = engine.analyze(analyzed.fen, settings.thinkTimeMillis)
                if (analyzed != position) return@launch
                suggestion = analysis
                state = idleState
                if (speak) speaker.say(listOfNotNull(announcement, sentence(analysis)).joinToString(". "))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (analyzed == position) state = TrackingState.Unsure(e.message ?: "Stockfish failed.")
            }
        }
    }

    private fun sentence(a: EngineAnalysis): String? {
        val move = UciMove.parse(a.bestMove) ?: return null
        var text = "Play " + position.board.spokenDescription(move)
        if (a.mate != null && a.mate > 0) text += ". Mate in ${a.mate}"
        else if (settings.speakEvaluation && a.centipawns != null) text += String.format(". Evaluation %+.1f", a.centipawns / 100.0)
        return text
    }

    private fun resyncTracker() {
        if (mode != PlayMode.GLASSES || !isCalibrated) return
        val frame = glasses.latestFrame ?: return
        val board = position.board
        viewModelScope.launch {
            val c = withContext(vision) { tracker.resync(frame, board) }
            if (c != null) corners = c else state = TrackingState.Lost
        }
    }

    private fun warn(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarning < 20_000) return
        lastWarning = now
        speaker.say(text)
    }

    val engineBundled: Boolean get() = engine.isBundled
}

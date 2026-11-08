package com.chesslens.android.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesslens.android.GameViewModel
import com.chesslens.android.PlayMode
import com.chesslens.android.TrackingState
import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.Piece
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.Square
import com.chesslens.android.chess.UciMove
import com.chesslens.android.vision.BoardCorners
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch

@Composable
fun ChessLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3B5BDB))) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val LightSquare = Color(0xFFEDD9B8)
private val DarkSquare = Color(0xFFB08763)
private val Accent = Color(0xFFFACC33)

@Composable
fun MainScreen(
    game: GameViewModel,
    activity: Activity,
    requestGlassesPermission: suspend (Permission) -> PermissionStatus,
    keepScreenOn: (Boolean) -> Unit,
) {
    val streamState by game.glasses.streamState.collectAsState()
    val registration by game.glasses.registrationState.collectAsState()
    val error by game.glasses.error.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Square?>(null) }
    var showFen by remember { mutableStateOf(false) }

    LaunchedEffect(streamState) { keepScreenOn(streamState == StreamState.STREAMING) }

    val calibration = game.calibration
    if (calibration != null) {
        CalibrationScreen(game)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ChessLens", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PlayMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = game.mode == mode,
                    onClick = { game.changeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, PlayMode.entries.size),
                ) { Text(mode.label) }
            }
        }

        if (game.mode == PlayMode.GLASSES) {
            GlassesCard(game, activity, registration, streamState, requestGlassesPermission)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { game.checkNow() }, enabled = game.isCalibrated && streamState == StreamState.STREAMING, modifier = Modifier.weight(1f)) { Text("Check now") }
                OutlinedButton(onClick = { game.undo() }, enabled = game.history.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo") }
                OutlinedButton(onClick = { game.newGame() }, modifier = Modifier.weight(1f)) { Text("New game") }
            }
            OutlinedButton(onClick = { game.beginCalibration() }, enabled = streamState == StreamState.STREAMING, modifier = Modifier.fillMaxWidth()) {
                Text("Set board corners by hand")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { game.undo() }, enabled = game.history.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo") }
                OutlinedButton(onClick = { game.newGame() }, modifier = Modifier.weight(1f)) { Text("New game") }
            }
        }

        StatusLine(game)
        SuggestionCard(game)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (game.mode == PlayMode.SCREEN) "Tap a piece, then its square, to play moves for both sides. Long-press a square to change its piece."
                    else "Game position. If a move was missed, tap the piece then its square. Long-press to change a piece.",
                    style = MaterialTheme.typography.bodySmall,
                )
                BoardView(
                    board = game.position.board,
                    bottom = game.playerColor,
                    highlight = game.suggestion?.let { UciMove.parse(it.bestMove) },
                    selected = game.selected,
                    flagged = if (game.mode == PlayMode.GLASSES) game.mismatched else emptyList(),
                    onTap = { game.tap(it) },
                    onLongPress = { editing = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { game.toggleSideToMove() }) { Text("${game.position.sideToMove.displayName} to move") }
                    OutlinedButton(onClick = { showFen = true }) { Text("FEN") }
                }
                if (game.moveLog.isNotEmpty()) {
                    Text(game.moveLog.joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(game, activity) { showSettings = false }
    editing?.let { square -> PiecePicker(square, onPick = { game.setPiece(it, square); editing = null }, onDismiss = { editing = null }) }
    if (showFen) FenDialog(game.position.fen, onApply = { game.applyFen(it); showFen = false }, onDismiss = { showFen = false })
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { game.glasses.error.value = null },
            confirmButton = { TextButton(onClick = { game.glasses.error.value = null }) { Text("OK") } },
            title = { Text("Glasses") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun GlassesCard(
    game: GameViewModel,
    activity: Activity,
    registration: RegistrationState,
    streamState: StreamState,
    requestGlassesPermission: suspend (Permission) -> PermissionStatus,
) {
    val preview by game.glasses.preview.collectAsState()
    val hasDevice by game.glasses.hasActiveDevice.collectAsState()
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ray-Ban Meta", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    registration != RegistrationState.REGISTERED -> "Not connected to Meta AI"
                    !hasDevice -> "Put on your glasses"
                    streamState == StreamState.STREAMING -> "Streaming from glasses"
                    streamState == StreamState.PAUSED -> "Stream paused (tap glasses to resume)"
                    streamState == StreamState.STOPPED -> "Glasses ready"
                    else -> "Starting camera…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            preview?.let { bitmap ->
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
                        Image(bitmap.asImageBitmap(), contentDescription = "Glasses camera", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        val outline = game.corners ?: game.searchOutline
                        if (outline != null) GridOverlay(outline, if (game.corners != null) Color.Green else Color(0xFFFF9800))
                    }
                }
            }
            when {
                registration != RegistrationState.REGISTERED ->
                    Button(onClick = { game.glasses.connect(activity) }, enabled = registration != RegistrationState.REGISTERING, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect glasses via Meta AI")
                    }
                streamState != StreamState.STOPPED ->
                    OutlinedButton(onClick = { game.glasses.stopStreaming() }, modifier = Modifier.fillMaxWidth()) { Text("Stop glasses camera") }
                else ->
                    Button(onClick = { scope.launch { game.glasses.startStreaming(requestGlassesPermission) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start glasses camera")
                    }
            }
        }
    }
}

/** The board's 8×8 grid over the preview: green while tracking, orange while confirming. */
@Composable
private fun GridOverlay(corners: BoardCorners, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        fun p(u: Double, v: Double): Offset {
            val pt = corners.point(u, v)
            return Offset((pt.x * size.width).toFloat(), (pt.y * size.height).toFloat())
        }
        for (k in 0..8) {
            val t = k / 8.0
            drawLine(color, p(t, 0.0), p(t, 1.0), strokeWidth = 2f)
            drawLine(color, p(0.0, t), p(1.0, t), strokeWidth = 2f)
        }
    }
}

@Composable
private fun StatusLine(game: GameViewModel) {
    val problem = game.state is TrackingState.Lost || game.state is TrackingState.Unsure
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (game.state == TrackingState.Thinking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(game.statusText, style = MaterialTheme.typography.bodySmall, color = if (problem) Color.Red else Color.Gray)
        }
        game.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        if (game.mode == PlayMode.GLASSES && game.mismatched.isNotEmpty()) {
            Text(
                "The camera sees something different on ${game.mismatched.joinToString { it.algebraic }} (red on the board). " +
                    "If a move was missed, tap it on the board; long-press a square to fix a piece.",
                style = MaterialTheme.typography.bodySmall, color = Color.Red,
            )
        }
    }
}

@Composable
private fun SuggestionCard(game: GameViewModel) {
    val best = game.suggestionText() ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Best move for ${game.position.sideToMove.displayName}", style = MaterialTheme.typography.labelSmall)
                    Text(best, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    game.spokenSuggestion()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                game.evaluationText()?.let {
                    Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(Color(0x22000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            game.lineText()?.let { Text("Line: $it", style = MaterialTheme.typography.bodySmall) }
            game.suggestion?.depth?.let { Text("Stockfish depth $it", style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (game.mode == PlayMode.SCREEN && game.isPlayersTurn) Button(onClick = { game.playSuggestion() }) { Text("Play it") }
                OutlinedButton(onClick = { game.speakAgain() }) { Text("Say it again") }
            }
        }
    }
}

@Composable
fun BoardView(
    board: ChessBoard,
    bottom: PieceColor,
    highlight: UciMove?,
    selected: Square?,
    flagged: List<Square>,
    onTap: (Square) -> Unit,
    onLongPress: (Square) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        val cell = maxWidth / 8
        Column {
            for (row in 0 until 8) {
                Row {
                    for (col in 0 until 8) {
                        val square = if (bottom == PieceColor.WHITE) Square(col, 7 - row) else Square(7 - col, row)
                        val base = if (square.isLight) LightSquare else DarkSquare
                        val lit = highlight != null && (highlight.from == square || highlight.to == square)
                        var modifier = Modifier.size(cell).background(if (lit) Accent.copy(alpha = 0.75f) else base)
                        if (square in flagged) modifier = modifier.border(3.dp, Color.Red)
                        if (square == selected) modifier = modifier.border(3.dp, Color.Blue)
                        Box(
                            modifier = modifier.pointerInput(square) {
                                detectTapGestures(onTap = { onTap(square) }, onLongPress = { onLongPress(square) })
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            board[square]?.let { Text(it.symbol, fontSize = (cell.value * 0.7f).sp, color = Color.Black) }
                            val labelColor = if (square.isLight) DarkSquare else LightSquare
                            if (square.rank == (if (bottom == PieceColor.WHITE) 0 else 7)) {
                                Text("${'a' + square.file}", fontSize = 9.sp, color = labelColor, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                            }
                            if (square.file == (if (bottom == PieceColor.WHITE) 0 else 7)) {
                                Text("${square.rank + 1}", fontSize = 9.sp, color = labelColor, modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(game: GameViewModel, activity: Activity, onDismiss: () -> Unit) {
    val s = game.settings
    var handsFree by remember { mutableStateOf(s.handsFree) }
    var announce by remember { mutableStateOf(s.announceOpponent) }
    var speakEval by remember { mutableStateOf(s.speakEvaluation) }
    var think by remember { mutableStateOf(s.thinkTimeMillis) }
    var interval by remember { mutableStateOf(s.checkIntervalSeconds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("I'm playing", fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PieceColor.entries.forEachIndexed { i, color ->
                        SegmentedButton(selected = game.playerColor == color, onClick = { game.changePlayerColor(color) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(color.displayName) }
                    }
                }
                Stepper("Stockfish think time", "%.1f s".format(think / 1000.0),
                    onMinus = { think = (think - 500).coerceAtLeast(500); s.thinkTimeMillis = think },
                    onPlus = { think = (think + 500).coerceAtMost(10_000); s.thinkTimeMillis = think })
                Text("Glasses", fontWeight = FontWeight.SemiBold)
                Toggle("Hands-free tracking", handsFree) { handsFree = it; s.handsFree = it }
                Stepper("Check the board every", "$interval s",
                    onMinus = { interval = (interval - 1).coerceAtLeast(2); s.checkIntervalSeconds = interval },
                    onPlus = { interval = (interval + 1).coerceAtMost(15); s.checkIntervalSeconds = interval })
                Toggle("Say the opponent's move", announce) { announce = it; s.announceOpponent = it }
                Toggle("Say the evaluation", speakEval) { speakEval = it; s.speakEvaluation = it }
                if (game.glasses.isRegistered) {
                    OutlinedButton(onClick = { game.glasses.disconnect(activity) }) { Text("Disconnect from Meta AI") }
                }
                Text(
                    "Engine: Stockfish 17 on this phone (${if (game.engineBundled) "bundled" else "missing"}). Board reading runs on this phone; no images leave it. " +
                        "Stockfish is free software under the GNU GPL v3 (stockfishchess.org).",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        TextButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun PiecePicker(square: Square, onPick: (Piece?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Empty square") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Piece on ${square.algebraic}") },
        text = {
            Column {
                for (color in PieceColor.entries) {
                    Row {
                        Piece.all.filter { it.color == color }.forEach { piece ->
                            TextButton(onClick = { onPick(piece) }) { Text(piece.symbol, fontSize = 26.sp) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun FenDialog(initial: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Position (FEN)") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) },
    )
}

/** Fallback: drag four handles onto the corners of the 64 squares. */
@Composable
private fun CalibrationScreen(game: GameViewModel) {
    val draft = game.calibration ?: return
    val labels = listOf("a1", "h1", "h8", "a8")
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set board corners", style = MaterialTheme.typography.titleLarge)
        Text("Drag each dot onto that corner of the 64 squares (the playing area, not the outer border). The grid should line up with the squares.", style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(draft.bitmap.width.toFloat() / draft.bitmap.height)) {
                Image(draft.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                GridOverlay(draft.corners, Color.Yellow)
                Canvas(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        var active = -1
                        detectDragGestures(
                            onDragStart = { start ->
                                val pts = game.calibration?.corners?.points ?: return@detectDragGestures
                                active = pts.indices.minByOrNull { i ->
                                    val dx = pts[i].x * size.width - start.x; val dy = pts[i].y * size.height - start.y
                                    dx * dx + dy * dy
                                } ?: -1
                            },
                            onDrag = { change, _ ->
                                if (active >= 0) game.moveCalibrationCorner(active, change.position.x / size.width.toDouble(), change.position.y / size.height.toDouble())
                            },
                        )
                    },
                ) {
                    draft.corners.points.forEachIndexed { i, p ->
                        val c = Offset((p.x * size.width).toFloat(), (p.y * size.height).toFloat())
                        drawCircle(Color(0x663B5BDB), radius = 40f, center = c)
                        drawCircle(Color(0xFF3B5BDB), radius = 8f, center = c)
                    }
                    val path = Path().apply {
                        val pts = draft.corners.points.map { Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat()) }
                        moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
                    }
                    drawPath(path, Color(0xFF3B5BDB), style = Stroke(width = 3f))
                }
            }
        }
        Text("Corners in order: ${labels.joinToString(", ")}. The position on the board must match the app (normally the starting position).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { game.cancelCalibration() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { game.finishCalibration() }, modifier = Modifier.weight(1f)) { Text("Start") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

package com.chesslens.android.vision

import com.chesslens.android.chess.ChessBoard
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.UciMove
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VisionTest {
    /** Renders [board] seen at an angle: board point (u, v) appears at corners.point(u, v). */
    private fun render(board: ChessBoard, corners: BoardCorners, w: Int = 504, h: Int = 896, seed: Int = 1): Frame {
        val toBoard = Homography.fit(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)),
            corners.points.map { Pt(it.x * w, it.y * h) })!!.inverse()!!
        val rnd = Random(seed)
        val luma = ByteArray(w * h)
        val clutter = List(25) { listOf(rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(10, 150), rnd.nextInt(10, 150), rnd.nextInt(256)) }
        for (y in 0 until h) for (x in 0 until w) {
            val b = toBoard.apply(Pt(x + 0.5, y + 0.5))
            var v: Double
            if (b.x in -0.05..1.05 && b.y in -0.05..1.05) {
                if (b.x !in 0.0..1.0 || b.y !in 0.0..1.0) v = 70.0 // frame/border
                else {
                    val f = (b.x * 8).toInt().coerceIn(0, 7); val r = (b.y * 8).toInt().coerceIn(0, 7)
                    v = if ((f + r) % 2 == 1) 225.0 else 120.0
                    // pieces: a disc plus a "head" toward the far side (tall piece silhouette)
                    for (df in -1..1) for (dr in -1..0) {
                        val pf = f + df; val pr = r + dr
                        if (pf !in 0..7 || pr !in 0..7) continue
                        val piece = board.at(pr * 8 + pf) ?: continue
                        val cx = (pf + 0.5) / 8; val cy = (pr + 0.45) / 8
                        val inBody = hypot(b.x - cx, b.y - cy) < 0.32 / 8
                        val inHead = hypot(b.x - cx, b.y - (cy + 0.55 / 8)) < 0.18 / 8 || (kotlin.math.abs(b.x - cx) < 0.1 / 8 && b.y in cy..cy + 0.5 / 8)
                        if (inBody || inHead) v = if (piece.color == PieceColor.WHITE) 248.0 else 35.0
                    }
                }
            } else {
                v = 140.0
                for (c in clutter) if (x in c[0]..c[0] + c[2] && y in c[1]..c[1] + c[3]) v = c[4].toDouble()
            }
            v += rnd.nextDouble(-6.0, 6.0)
            luma[y * w + x] = v.coerceIn(0.0, 255.0).toInt().toByte()
        }
        return Frame(w, h, luma)
    }

    private fun seated(cx: Double, cy: Double, size: Double, tilt: Double, rot: Double): BoardCorners {
        val aspect = 504.0 / 896.0
        val pts = listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0).map { (u, v) ->
            val k = 1 - tilt * (0.5 - 0.5 * v)
            val x = u * size / 2 * k; val y = v * size / 2 * (1 - tilt * 0.35)
            val xr = x * cos(rot) - y * sin(rot); val yr = x * sin(rot) + y * cos(rot)
            Pt(cx + xr, cy + yr * aspect)
        }
        return BoardCorners.of(pts) // a1 bottom-left, h1 bottom-right, h8 top-right, a8 top-left
    }

    @Test fun detectsRandomMovesInRandomPositions() {
        var detected = 0; var wrong = 0; var recovered = 0; var wrongRecoveries = 0; var trials = 0
        var detectMillis = 0L
        for (t in 0 until 20) {
            val rnd = Random(200 + t)
            val truth = seated(0.5, 0.55, rnd.nextDouble(0.6, 0.85), rnd.nextDouble(0.0, 0.35), rnd.nextDouble(-0.25, 0.25))
            var position = ChessPosition.START
            repeat(rnd.nextInt(2, 30)) { val ms = position.legalMoves(); if (ms.isNotEmpty()) position = position.applying(ms.random(rnd)) }
            val legal = position.legalMoves()
            if (legal.isEmpty()) continue
            val move = legal.filter { it.promotion == null || it.promotion == com.chesslens.android.chess.PieceKind.QUEEN }.random(rnd)
            val after = position.applying(move)
            val tracker = BoardTracker()
            assertTrue(tracker.calibrate(render(position.board, truth, seed = t), truth, position.board))
            val observed = tracker.observe(render(after.board, truth.translated(rnd.nextDouble(-0.005, 0.005), rnd.nextDouble(-0.004, 0.004)), seed = 900 + t), position.sideToMove)
                as? TrackerResult.Observed ?: continue
            trials++
            val started = System.currentTimeMillis()
            val detection = MoveDetector.detect(position, observed.observation, PieceColor.WHITE)
            detectMillis += System.currentTimeMillis() - started
            when {
                detection is MoveDetection.Moves && detection.result.board == after.board -> detected++
                detection is MoveDetection.Moves -> { wrong++; println("WRONG trial $t: played ${move.uci}, detected ${detection.moves.map { it.uci }}") }
                else -> println("missed trial $t: played ${move.uci} -> ${detection::class.simpleName}")
            }
            val rec = MoveDetector.recover(position) { b -> BoardTracker.mismatchedSquares(observed.features, b, PieceColor.WHITE).size }
            if (rec?.second?.board == after.board) recovered++
            else if (rec != null) {
                wrongRecoveries++
                val flagsTrue = BoardTracker.mismatchedSquares(observed.features, after.board, PieceColor.WHITE).map { com.chesslens.android.chess.Square.fromIndex(it).algebraic }
                println("WRONG recovery trial $t: played ${move.uci}, recovered ${rec.first.map { it.uci }}; flags on the true board: $flagsTrue; fen ${after.fen}")
                val f = observed.features
                for (sq in listOf("a5", "a6", "a7", "a8", "b6", "h6")) { val i = com.chesslens.android.chess.Square.parse(sq)!!.index; println("  $sq piece=${after.board.at(i)?.fenChar} bright=%.3f tex=%.3f".format(f[i][0], f[i][1])) }
            }
        }
        println("random moves: detected $detected/$trials, wrong $wrong, recovered by verification $recovered/$trials (wrong $wrongRecoveries), avg detect ${detectMillis / maxOf(trials, 1)} ms")
        assertEquals(0, wrong)
        assertEquals(0, wrongRecoveries)
        assertTrue(detected >= trials * 3 / 4)
    }

    @Test fun findsBoardAndDetectsMoves() {
        var found = 0; var moves = 0; var recovered = 0; val trials = 6
        for (t in 0 until trials) {
            val rnd = Random(t)
            val truth = seated(0.5 + rnd.nextDouble(-0.05, 0.05), 0.55 + rnd.nextDouble(-0.05, 0.05), rnd.nextDouble(0.6, 0.85), rnd.nextDouble(0.0, 0.35), rnd.nextDouble(-0.25, 0.25))
            val start = ChessPosition.START
            val frame = render(start.board, truth, seed = t)
            val tracker = BoardTracker()
            val located = tracker.locateBoard(frame, start.board) ?: continue
            if (located.first.distance(truth) < 0.02) found++ else continue
            assertTrue(tracker.calibrate(frame, located.first, start.board))

            // Play 1. e4: the camera (slightly moved) sees the new position.
            val move = UciMove.parse("e2e4")!!
            val after = start.applying(move)
            val moved = render(after.board, truth.translated(0.004, -0.003), seed = 100 + t)
            val observed = tracker.observe(moved, PieceColor.WHITE) as? TrackerResult.Observed ?: continue
            val detection = MoveDetector.detect(start, observed.observation, PieceColor.WHITE)
            if (detection is MoveDetection.Moves && detection.moves == listOf(move)) moves++
            else {
                val z = MoveDetector.robustZScores(observed.observation.differences)
                val top = (0 until 64).sortedByDescending { z[it] }.take(6).joinToString { "${com.chesslens.android.chess.Square.fromIndex(it).algebraic}=%.1f".format(z[it]) }
                println("trial $t: detection=${detection::class.simpleName} ${(detection as? MoveDetection.Moves)?.moves?.map { it.uci }} top z: $top")
            }
            // Recovery from the per-square check alone (as when the comparison missed the move).
            val rec = MoveDetector.recover(start) { b -> BoardTracker.mismatchedSquares(observed.features, b, PieceColor.WHITE).size }
            if (rec?.second?.board == after.board) recovered++
        }
        println("synthetic: board found $found/$trials, move detected $moves/$trials, recovered by verification $recovered/$trials")
        assertTrue("board found in most trials", found >= trials - 1)
        assertTrue("move detected whenever the board was found", moves >= found - 1)
    }

    @Test fun noPhantomMovesWhenNothingChanges() {
        var phantom = 0; var checks = 0
        for (t in 0 until 6) {
            val rnd = Random(50 + t)
            val truth = seated(0.5, 0.55, rnd.nextDouble(0.6, 0.85), rnd.nextDouble(0.0, 0.35), rnd.nextDouble(-0.25, 0.25))
            var position = ChessPosition.START
            repeat(rnd.nextInt(0, 12)) { position = position.applying(position.legalMoves().random(rnd)) }
            val tracker = BoardTracker()
            assertTrue(tracker.calibrate(render(position.board, truth, seed = t), truth, position.board))
            for (k in 0 until 3) {
                val shifted = truth.translated(rnd.nextDouble(-0.006, 0.006), rnd.nextDouble(-0.004, 0.004))
                val observed = tracker.observe(render(position.board, shifted, seed = 300 + 10 * t + k), PieceColor.WHITE) as? TrackerResult.Observed ?: continue
                checks++
                if (MoveDetector.detect(position, observed.observation, PieceColor.WHITE) is MoveDetection.Moves) phantom++
            }
        }
        println("no-move checks: $checks, phantom moves: $phantom")
        assertEquals(0, phantom)
    }

    /**
     * Runs the grid detector over photos converted to raw grayscale files named
     * `<name>_<width>x<height>.gray` (set CHESSLENS_TEST_PHOTOS), writing the outlines found to
     * `outlines.txt` (set CHESSLENS_TEST_OUT) so they can be drawn over the photos.
     */
    @Test fun realPhotos() {
        val dir = System.getenv("CHESSLENS_TEST_PHOTOS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(System.getenv("CHESSLENS_TEST_OUT") ?: "build/vision-out").apply { mkdirs() }
        val pattern = Regex("(.+)_(\\d+)x(\\d+)\\.gray")
        val report = StringBuilder()
        var found = 0
        val files = dir!!.listFiles()!!.filter { pattern.matches(it.name) }.sorted()
        for (file in files) {
            val (name, w, h) = pattern.matchEntire(file.name)!!.destructured
            val outlines = ChessGridDetector.boardOutlines(Frame(w.toInt(), h.toInt(), file.readBytes()))
            if (outlines.isNotEmpty()) {
                found++
                val c = BoardCorners.counterClockwise(outlines.first())
                report.append(name).append(' ').append(c.joinToString(" ") { "${it.x},${it.y}" }).append('\n')
            } else report.append(name).append(" none\n")
        }
        File(out, "outlines.txt").writeText(report.toString())
        println("real photos: grid found on $found/${files.size}")
        assertTrue(found > 0)
    }
}

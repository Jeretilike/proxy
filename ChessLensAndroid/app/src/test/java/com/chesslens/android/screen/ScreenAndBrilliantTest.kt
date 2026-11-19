package com.chesslens.android.screen

import com.chesslens.android.chess.Brilliant
import com.chesslens.android.chess.ChessPosition
import com.chesslens.android.chess.EngineLine
import com.chesslens.android.chess.PieceColor
import com.chesslens.android.chess.UciMove
import com.chesslens.android.vision.PieceTemplate
import com.chesslens.android.vision.RgbImage
import com.chesslens.android.vision.ScreenGrid
import com.chesslens.android.vision.ScreenVision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ScreenAndBrilliantTest {
    private fun pos(fen: String) = ChessPosition.fromFen(fen)!!
    private fun move(uci: String) = UciMove.parse(uci)!!

    private val legal = "rn1qkbnr/ppp2p1p/3p2p1/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5"
    private val greek = "rnbq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 0 7"

    @Test fun sacrifices() {
        assertTrue("Légal trap Nxe5 gives the queen", Brilliant.isSacrifice(pos(legal), move("f3e5")))
        assertFalse(Brilliant.isSacrifice(pos(legal), move("e1g1")))
        assertFalse(Brilliant.isSacrifice(ChessPosition.START, move("g1f3")))
        assertFalse("even trade", Brilliant.isSacrifice(pos("r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3"), move("b5c6")))
        assertEquals("Greek gift: bishop for a pawn", 2, Brilliant.sacrificed(pos(greek), move("d3h7")).first)
        assertTrue(Brilliant.isSacrifice(pos(greek), move("d3h7")))
        assertFalse("pawn gambit", Brilliant.isSacrifice(pos("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"), move("f2f4")))
        assertEquals("promotion costs only the pawn", 1, Brilliant.sacrificed(pos("3r2k1/4P3/8/8/8/8/8/6K1 w - - 0 1"), move("e7e8q")).first)
    }

    @Test fun choosing() {
        val p = pos(legal)
        val lines = listOf(EngineLine("e1g1", 80, null, emptyList()), EngineLine("f3e5", 60, null, emptyList()), EngineLine("h2h3", 40, null, emptyList()))
        assertEquals("e1g1" to false, Brilliant.choose(p, lines, false)!!.let { it.first.move to it.second })
        assertEquals("f3e5" to true, Brilliant.choose(p, lines, true)!!.let { it.first.move to it.second })
        val worse = listOf(EngineLine("e1g1", 150, null, emptyList()), EngineLine("f3e5", -100, null, emptyList()))
        assertEquals("e1g1" to false, Brilliant.choose(p, worse, true)!!.let { it.first.move to it.second })
        val winning = listOf(EngineLine("e1g1", 900, null, emptyList()), EngineLine("f3e5", 890, null, emptyList()))
        assertFalse(Brilliant.choose(p, winning, true)!!.second)
        val mating = listOf(EngineLine("f3e5", null, 4, emptyList()), EngineLine("e1g1", 900, null, emptyList()))
        assertEquals("f3e5" to true, Brilliant.choose(p, mating, true)!!.let { it.first.move to it.second })
    }

    /** Fake chess-app screenshots made by ChessLensPC/test/make-android-fixtures.mjs. */
    @Test fun readsBoardsFromScreenshots() {
        val dir = System.getenv("CHESSLENS_SCREEN_SHOTS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val shots = dir!!.listFiles { f -> f.name.endsWith(".rgb") }!!.sortedBy { it.name }
        val groups = shots.groupBy { it.name.substringBefore("_").substringBeforeLast("-") }
        var exact = 0
        var total = 0
        var mover = 0
        for ((tag, files) in groups) {
            var templates: Map<String, PieceTemplate> = emptyMap()
            var grid: ScreenGrid? = null
            var whiteAtBottom = true
            for (file in files) {
                val (w, h) = file.name.substringAfterLast("_").removeSuffix(".rgb").split("x").map { it.toInt() }
                val bytes = file.readBytes()
                val img = RgbImage(w, h, IntArray(w * h) { i ->
                    ((bytes[i * 3].toInt() and 255) shl 16) or ((bytes[i * 3 + 1].toInt() and 255) shl 8) or (bytes[i * 3 + 2].toInt() and 255)
                })
                val info = File(file.parentFile, file.name.substringBeforeLast("_") + ".txt").readLines()
                val fen = info[0]
                val bottom = info[1]
                val selected = info.getOrNull(2) == "selected"
                val found = ScreenVision.findBoard(img, grid)
                assertNotNull("$tag: board not found in ${file.name}", found)
                grid = found
                val squares = ScreenVision.readSquares(img, found!!)
                if (file.name.contains("-0start")) {
                    val lesson = ScreenVision.learnFromStart(squares)
                    assertNotNull("$tag: start position not recognised", lesson)
                    templates = lesson!!.first
                    whiteAtBottom = lesson.second
                    assertEquals("$tag orientation", bottom.toBoolean(), whiteAtBottom)
                    println("$tag: board at ${"%.1f".format(found.x)},${"%.1f".format(found.y)} cell ${"%.2f".format(found.cw)}")
                    continue
                }
                total++
                val reading = ScreenVision.recognize(squares, templates, whiteAtBottom)
                if (reading == null) { println("${file.name}: unreadable square"); continue }
                val want = fen.split(" ")[0]
                if (reading.board.placementFen == want) exact++ else println("${file.name}: read ${reading.board.placementFen}\n   wanted $want")
                val justMoved = if (fen.split(" ")[1] == "w") PieceColor.BLACK else PieceColor.WHITE
                // With a tapped piece also highlighted, the highlight mustn't be read as a move.
                val expected = if (selected) null else justMoved
                if (reading.lastMover == expected) mover++ else println("${file.name}: last mover ${reading.lastMover}, expected $expected")
            }
        }
        println("screenshots read exactly: $exact/$total; side that just moved right: $mover/$total")
        assertEquals(total, exact)
        assertEquals(total, mover)
    }

    /** A frame marked by hand a few pixels off still reads the board exactly. */
    @Test fun handMarkedFrameSnapsToTheBoard() {
        val dir = System.getenv("CHESSLENS_SCREEN_SHOTS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        fun load(f: File): RgbImage {
            val (w, h) = f.name.substringAfterLast("_").removeSuffix(".rgb").split("x").map { it.toInt() }
            val b = f.readBytes()
            return RgbImage(w, h, IntArray(w * h) { i -> ((b[i * 3].toInt() and 255) shl 16) or ((b[i * 3 + 1].toInt() and 255) shl 8) or (b[i * 3 + 2].toInt() and 255) })
        }
        val start = load(dir!!.listFiles()!!.first { it.name.startsWith("green-w-0start") && it.name.endsWith(".rgb") })
        val game = dir.listFiles()!!.first { it.name.startsWith("green-w-2_") }
        val exact = ScreenVision.findBoard(start)!!
        // Off by 3 pixels and slightly too big, as a finger might leave it.
        val marked = ScreenGrid(exact.x + 3, exact.y - 3, exact.cw * 1.02, exact.ch * 1.02)
        val snapped = ScreenVision.snap(start, marked)
        println("marked ${"%.1f".format(marked.x)},${"%.1f".format(marked.y)} cell ${"%.2f".format(marked.cw)} -> snapped ${"%.1f".format(snapped.x)},${"%.1f".format(snapped.y)} cell ${"%.2f".format(snapped.cw)} (exact ${"%.1f".format(exact.x)},${"%.1f".format(exact.y)} cell ${"%.2f".format(exact.cw)})")
        val templates = ScreenVision.learnFromStart(ScreenVision.readSquares(start, snapped))!!.first
        val img = load(game)
        val reading = ScreenVision.recognize(ScreenVision.readSquares(img, ScreenVision.snap(img, marked)), templates, true)!!
        val want = File(game.parentFile, game.name.substringBeforeLast("_") + ".txt").readLines()[0].split(" ")[0]
        assertEquals(want, reading.board.placementFen)
    }
}

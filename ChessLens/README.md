# ChessLens

An iPhone app that watches a real chess game through your **Ray-Ban Meta (Gen 2)** glasses and speaks the best move, computed by **Stockfish 17** on the phone. Everything runs on the iPhone. There are no paid services and no images leave the phone.

```
glasses camera ─► board found & aligned ─► which squares changed? ─► matching legal move ─► Stockfish ─► spoken through the glasses
```

## How it works

The app doesn't try to recognise pieces from scratch. It **follows the game from a known position**:

1. **Calibrate once.** With the pieces in the starting position (or any position you've entered), look at the board and mark its four corners on the phone.
2. **Watch.** Whenever your view holds still for about a second, the app aligns the new frame with the last confirmed view (your head moves). It warps the board to a top-down image and measures how much each of the 64 squares changed.
3. **Match a legal move.** It tries every legal move, and every move-plus-reply in case you looked away while both were played. It picks the one whose changed squares fit what the camera saw. It also uses a simple empty / White / Black guess per square. A move is only accepted if a second frame agrees.
4. **Speak.** After your opponent's move it says something like *"Opponent: knight g8 to f6. Play bishop f1 to b5."* After your own move it stays quiet.

If it can't tell what happened, it says *"I didn't catch that move"*. You can then tap the move on the phone, and long-press any square to fix a piece.

> **Status: untested on real glasses.** The move-matching logic was checked in a simulation, and the move generator against standard chess test positions (perft). The camera side (board alignment with a head-mounted camera, lighting, how pieces cover each other) has not been tried on real hardware yet. Expect to tune it.

---

## What you need

- Ray-Ban Meta glasses, paired and working in the **Meta AI** app
- An iPhone on **iOS 17.2 or later**
- A free **Apple ID**: no paid developer account needed
- **SideStore** to install the app (see below)

## Install

ChessLens isn't on the App Store or TestFlight: both need a paid Apple Developer account. Instead GitHub builds it, and **SideStore** installs it signed with your own free Apple ID.

### 1. Turn on Developer Mode for your glasses
Meta AI app → **Settings → your glasses → Developer Mode** → on. This lets ChessLens use the glasses camera.

### 2. Install SideStore
- **iOS 27:** a third-party tool, **SideInstaller**, reportedly sets SideStore up on the iPhone itself using iOS 27's wireless pairing. It isn't official, so check its GitHub page first.
- **iOS 18–26:** SideStore's first-time setup needs a computer once (Windows, Mac or Linux, for example with iLoader). After that everything happens on the phone.

Follow SideStore's own instructions at sidestore.io.

### 3. Download ChessLens
On your iPhone, open this repository's **Releases** page on github.com and download **ChessLens.ipa** from *ChessLens (latest build)*. It's saved to the Files app.

Every push that changes `ChessLens/` rebuilds it automatically. You can also start a build from the GitHub app: **Actions → ChessLens iOS → Run workflow**.

### 4. Install it with SideStore
SideStore → **My Apps** → **+** → pick `ChessLens.ipa` from Files.

**Free Apple ID limits:**
- Apps must be refreshed every 7 days. SideStore can do this on the phone.
- At most 3 sideloaded apps can be active at once.

## Play

1. **Settings** (gear icon): choose White or Black, and Stockfish's think time.
2. **Connect glasses via Meta AI**: approve ChessLens in the Meta AI app.
3. **Start glasses camera**: the first time, allow camera access in Meta AI.
4. Set up the starting position. Sit normally and look at the whole board. Tap **Calibrate board** and drag the four dots onto the corners of the 64 squares, then tap **Start**. If you're White, it speaks your first move.
5. Play. Glance at the board after each move and hold still for a moment.

Tips:
- Keep the whole board in view when you check it. A slightly higher viewing angle works better.
- Even light helps; avoid strong shadows from your hands or head across the board.
- The yellow outline on the preview shows where the app thinks the board is. If it drifts, tap **Recalibrate board**.
- **Check now** forces a look right away. **Undo** takes back a wrongly detected move.
- The phone stays awake while the glasses camera runs. Keep ChessLens open (the phone can lie face down).
- Most clubs and tournaments don't allow engine help during a game. Use it for practice and casual games.

---

## Project layout

| Part | Code |
|---|---|
| Glasses connection and camera stream | `ChessLens/Glasses/GlassesManager.swift` (Meta Wearables Device Access Toolkit 1.0.1) |
| Board alignment, warping, per-square measurements | `ChessLens/Vision/BoardTracker.swift` |
| Which legal move explains the changes | `ChessLens/Vision/MoveDetector.swift` |
| When to look (view settled) | `ChessLens/Vision/SceneChangeDetector.swift` |
| Rules: legal moves, FEN, move wording | `ChessLens/Chess/ChessPosition.swift`, `ChessLens/Chess/ChessBoard.swift` |
| Stockfish | `ChessLens/Engine/StockfishEngine.swift` (ChessKitEngine 0.7.0) |
| Game flow, speech, corrections | `ChessLens/ViewModels/GameViewModel.swift` |
| Calibration screen | `ChessLens/Views/CalibrationView.swift` |
| Project definition | `project.yml` (XcodeGen), `Config/` |
| CI build and download | `.github/workflows/chesslens-ios.yml` |

Stockfish's neural network files (~75 MB) are downloaded during the build by `scripts/download-nnue.sh` (checksum-verified) rather than stored in git.

**On a Mac (optional):** `brew install xcodegen && ./scripts/download-nnue.sh && xcodegen generate && open ChessLens.xcodeproj`.

## Troubleshooting

- **Glasses don't connect:** check Developer Mode (step 1), that the glasses are unfolded and connected in Meta AI, and that Bluetooth is on.
- **"Board not found":** look at the whole board, or recalibrate.
- **Moves aren't noticed:** hold your head still for about a second after a move, keep hands off the board, and try **Check now**.
- **Wrong move detected:** tap **Undo**, then tap the right move on the phone.

## Licenses

Stockfish is free software under the GNU GPL v3, bundled through ChessKitEngine (MIT). Because Stockfish is GPL, keep this source code available if you share the app. The Meta Wearables Device Access Toolkit is under Meta's Wearables Developer Terms.

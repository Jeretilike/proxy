# ChessLens for Android

The Android version of [ChessLens](../ChessLens/README.md): it watches a real chess game through your **Ray-Ban Meta (Gen 2)** glasses, or lets you play on the screen, and speaks the best move from **Stockfish 17** running on the phone. There are no paid services and no images leave the phone.

- **Glasses:** finds the board by itself (from the grid of corners where four squares meet), follows the game, and checks the board when your view settles and at least every 5 seconds. After your opponent's move it says the move and your best reply through the glasses. Each check also verifies every square; if a move was missed, it works out which one and catches up.
- **Screen:** tap the moves for both sides on the app's board. On your turn it says the best move; **Play it** makes that move for you.

## Install

No Play Store and no developer account needed.

1. On your Android phone (Android 12 or newer, 64-bit), open this repository's **Releases** page on github.com and download **ChessLens.apk** from *ChessLens for Android (latest build)*.
2. Open the downloaded file. Android asks you to allow installs from your browser or Files app the first time: allow it, then tap **Install**.
3. New versions install the same way, over the old one. Your settings are kept.

GitHub rebuilds the app on every change to `ChessLensAndroid/`. You can also start a build from the GitHub app: **Actions → ChessLens Android → Run workflow**.

## Set up the glasses

1. In the **Meta AI** app: **Settings → your glasses → Developer Mode** → on.
2. Open ChessLens and allow **Nearby devices** (Bluetooth) when asked.
3. Choose **Glasses** at the top, tap **Connect glasses via Meta AI** and approve ChessLens in Meta AI.
4. Tap **Start glasses camera**. The first time, allow camera access in Meta AI.
5. Set up the starting position and look at the whole board. A grid appears on the preview: orange while it confirms, then green, and it says *"Board found"*. If it doesn't, use **Set board corners by hand**.

## How it was checked

The Android code runs unit tests on every build:
- **Chess rules:** standard perft move counts on 5 test positions (start, Kiwipete, endgame, promotions, castling).
- **Board finding and move detection** on rendered boards seen at an angle with clutter and noise:
  - The board was found exactly in 6 of 6 cases.
  - Random moves in random positions were detected 20 of 20 times, with none wrong.
  - Slightly shifted no-change views produced no phantom moves.
- **Real photos:** the grid detector was also run over 30 real photos of wooden, vinyl, tournament and on-screen boards, and found the grid on most of them. Folding boards that don't lie flat are its weak spot.

Not yet tried: real Ray-Ban Meta glasses on Android, and the Stockfish binary on a phone (it's compiled by the CI with Google's NDK).

## Project layout

| Part | Code |
|---|---|
| Glasses connection and camera frames | `app/src/main/java/com/chesslens/android/glasses/GlassesManager.kt` (Meta Wearables Device Access Toolkit 1.0.1) |
| Board finding (grid of square corners) | `vision/ChessGridDetector.kt` |
| Tracking, per-square measurements, verification | `vision/BoardTracker.kt` |
| Which move was played; missed-move recovery | `vision/MoveDetector.kt` |
| Chess rules | `chess/Chess.kt`, `chess/ChessPosition.kt` |
| Stockfish (separate process, UCI) | `engine/StockfishEngine.kt` |
| Game flow, speech, settings | `GameViewModel.kt`, `Services.kt` |
| Screens (Jetpack Compose) | `ui/` |
| CI build and download | `../.github/workflows/chesslens-android.yml` |

**Build on a computer:**
1. Build Stockfish with the NDK: `make build ARCH=armv8 COMP=ndk` in Stockfish 17's `src`.
2. Copy the result to `app/src/main/jniLibs/arm64-v8a/libstockfish.so`.
3. Run `./gradlew assembleRelease`.

The app is signed with a fixed key (`app/chesslens-debug.keystore`, standard Android debug passwords) so updates install over each other. It's meant for personal sideloading, not for publishing.

## Licenses

Stockfish is free software under the GNU GPL v3; keep this source available if you share the app. The Meta Wearables Device Access Toolkit is under Meta's Wearables Developer Terms.

# ChessLens

An iPhone app that looks at a real chess board through your **Ray-Ban Meta (Gen 2)** glasses and tells you the best move, computed by **Stockfish 17** running on the phone. The move is spoken through the glasses' speakers, so it works hands-free.

```
glasses camera ──► photo ──► Claude (vision) reads the pieces ──► Stockfish on the iPhone ──► spoken move
```

- **Hands-free mode:** look at the board and hold still for about a second. When the position has changed, the app reads it and says the move ("Knight g1 to f3"). With *Only speak on my turn* on, it stays quiet after you make your own move and speaks again once your opponent replies.
- **Manual mode:** tap *Analyze from glasses*, or use the iPhone camera or a saved photo.
- **Fix mistakes:** the recognized position is shown on screen. Tap any square to change the piece, rotate the board, switch the side to move, or edit the FEN. Stockfish re-runs immediately.

Recognition uses the Claude API (your own API key). Each board read is one API call. The engine runs entirely on the phone.

---

## What you need

| | |
|---|---|
| Apple Developer Program membership | Required for TestFlight ($99/year, sign up at developer.apple.com) |
| Ray-Ban Meta glasses + the **Meta AI** app | Glasses paired and working in Meta AI |
| An Anthropic API key | console.anthropic.com → API Keys |
| This GitHub repository | The app is built on GitHub's Mac servers, so you don't need a Mac |

Everything below can be done on an iPhone in Safari (plus the GitHub, Meta AI and TestFlight apps).

---

## One-time setup (iPhone only)

### 1. Turn on Developer Mode for your glasses
Meta AI app → **Settings → your glasses → Developer Mode** → on.
This lets ChessLens use the glasses camera without registering an app with Meta.

### 2. Create an App Store Connect API key
1. In Safari open **appstoreconnect.apple.com** → **Users and Access** → **Integrations** → **App Store Connect API** → **Team Keys**.
2. Tap **+**, name it `GitHub`, set Access to **Admin**, then **Generate**.
3. Note the **Key ID** and the **Issuer ID** shown above the table.
4. Tap **Download** (you can only download it once). It saves an `AuthKey_XXXX.p8` file to the Files app.
5. To copy its contents: in Files, long-press the file → **Rename** → change `.p8` to `.txt` → open it → tap and hold → **Select All** → **Copy**.

### 3. Find your Team ID
developer.apple.com/account → **Membership details** → **Team ID** (10 characters).

### 4. Choose a bundle ID
Any unique reverse-domain name, e.g. `com.yourname.chesslens`. The build registers it for you.

### 5. Create a private repository for signing certificates
fastlane *match* stores your distribution certificate there, encrypted, so every build reuses it.
1. github.com/new → name it `chesslens-certificates` → **Private** → Create.
2. Create a token that can write to it: github.com/settings/personal-access-tokens/new → *Repository access*: only `chesslens-certificates` → *Permissions → Contents*: **Read and write** → Generate, then copy it.

### 6. Add the secrets to this repository
On github.com open this repository → **Settings → Secrets and variables → Actions → New repository secret**, and add:

| Secret | Value |
|---|---|
| `ASC_KEY_ID` | Key ID from step 2 |
| `ASC_ISSUER_ID` | Issuer ID from step 2 |
| `ASC_KEY_CONTENT` | The whole `.p8` text from step 2, including the `BEGIN`/`END` lines |
| `APPLE_TEAM_ID` | Team ID from step 3 |
| `BUNDLE_ID` | Bundle ID from step 4 |
| `MATCH_GIT_URL` | `https://github.com/<your-username>/chesslens-certificates.git` |
| `MATCH_GIT_TOKEN` | Token from step 5 |
| `MATCH_PASSWORD` | A long passphrase you make up (it encrypts the certificates; keep it safe) |

Optional: `META_APP_ID` and `META_CLIENT_TOKEN`, only if you later register the app in the Meta Wearables Developer Center to share it beyond your own glasses.

### 7. Run the build once (registers the bundle ID and creates certificates)
GitHub app → this repository → **Actions** → **ChessLens iOS** → **Run workflow** (leave *Sign the app and upload to TestFlight* checked).

The first run stops with *"Create the app first"* if the app doesn't exist in App Store Connect yet. That's expected.

### 8. Create the app in App Store Connect
appstoreconnect.apple.com → **Apps** → **+** → **New App**:
- Platform: iOS
- Name: something unique on the App Store, e.g. `ChessLens <your name>`
- Bundle ID: pick the one from step 4
- SKU: anything, e.g. `chesslens`

### 9. Run the workflow again
It builds, signs and uploads. After Apple finishes processing (usually 5–15 minutes) the build shows under **TestFlight** in App Store Connect.

### 10. Install with TestFlight
1. App Store Connect → your app → **TestFlight** → **Internal Testing** → **+** to create a group → add yourself.
2. Install the **TestFlight** app from the App Store and accept the invite. ChessLens appears there.

For each new version, run the workflow again; the build number goes up automatically.

---

## Using the app

1. **Settings** (gear icon): paste your Anthropic API key, choose whether you play White or Black, adjust Stockfish's think time.
2. **Connect glasses via Meta AI**: the Meta AI app opens; approve ChessLens and you're sent back.
3. **Start glasses camera**: the first time, Meta AI asks you to allow camera access.
4. Look at the board. In hands-free mode the move is spoken after the view holds still. Otherwise tap **Analyze from glasses**.

Tips:
- Sit so the whole board is in view; a slightly higher angle reads better than a flat one.
- The phone screen stays on while the glasses camera runs. Keep ChessLens open (the phone can lie face down).
- If a piece is misread, tap the square on screen to fix it. The engine re-runs straight away.
- *Reading effort* and *Model* in Settings trade accuracy for speed and cost. Opus at *Medium* is the default.
- Before using it in a game against someone, check the rules: most clubs and tournaments don't allow engine help during play.

---

## How it's built

| Part | Code |
|---|---|
| Glasses connection, camera stream, photo capture | `ChessLens/Glasses/GlassesManager.swift` (Meta Wearables Device Access Toolkit 1.0.1) |
| Board recognition | `ChessLens/Vision/BoardRecognizer.swift` (Claude Messages API, structured JSON output) |
| When to analyse in hands-free mode | `ChessLens/Vision/SceneChangeDetector.swift` |
| Position model, FEN, legality checks, move wording | `ChessLens/Chess/ChessBoard.swift` |
| Stockfish | `ChessLens/Engine/StockfishEngine.swift` (ChessKitEngine 0.7.0, Stockfish 17) |
| Pipeline and turn detection | `ChessLens/ViewModels/AnalyzerViewModel.swift` |
| Project definition | `project.yml` (XcodeGen), `Config/` |
| CI build and TestFlight upload | `.github/workflows/chesslens-ios.yml`, `fastlane/Fastfile` |

Every push that changes `ChessLens/` runs an unsigned build as a compile check. Stockfish's neural network files (~75 MB) are downloaded during the build by `scripts/download-nnue.sh` rather than stored in git.

**On a Mac (optional):** `brew install xcodegen && ./scripts/download-nnue.sh && xcodegen generate && open ChessLens.xcodeproj`.

## Troubleshooting

- **"Missing repository secret"**: one of the secrets in step 6 is missing or misspelled.
- **"Couldn't read builds… Create the app first"**: do step 8, then run the workflow again.
- **Certificate limit reached**: Apple allows only a few distribution certificates. Revoke an unused one at developer.apple.com → Certificates, then re-run.
- **Glasses don't connect**: make sure Developer Mode is on (step 1), the glasses are unfolded and connected in Meta AI, and Bluetooth is on.
- **"Board looks wrong"**: a square was misread into an impossible position (two kings, a pawn on the back rank…). Tap the square to fix it, or try again from a better angle.

## Licenses

Stockfish is free software under the GNU GPL v3. ChessLens bundles it through ChessKitEngine (MIT). Because Stockfish is GPL, keep this source code available if you distribute the app. The Meta Wearables Device Access Toolkit is under Meta's Wearables Developer Terms.

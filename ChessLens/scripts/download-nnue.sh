#!/usr/bin/env bash
# Downloads the Stockfish 17 neural networks that ChessKitEngine loads from the app bundle.
# They are ~75 MB, so they are fetched at build time instead of being committed.
set -euo pipefail

dest="$(cd "$(dirname "$0")/.." && pwd)/ChessLens/Resources/NNUE"
mkdir -p "$dest"

for net in nn-1111cefa1111.nnue nn-37f18f62d772.nnue; do
  if [[ -s "$dest/$net" ]]; then
    echo "$net already present"
    continue
  fi
  for url in \
    "https://tests.stockfishchess.org/api/nn/$net" \
    "https://github.com/official-stockfish/networks/raw/master/$net"; do
    echo "Downloading $net from $url"
    if curl -fsSL --retry 3 -o "$dest/$net.part" "$url"; then
      mv "$dest/$net.part" "$dest/$net"
      break
    fi
  done
  # Stockfish names each network after the first 12 hex digits of its SHA-256.
  expected="${net#nn-}"; expected="${expected%.nnue}"
  actual=$(shasum -a 256 "$dest/$net" 2>/dev/null | cut -c1-12 || true)
  if [[ "$actual" != "$expected" ]]; then
    echo "::error::Failed to download $net (checksum ${actual:-missing}, expected $expected)" >&2
    rm -f "$dest/$net"
    exit 1
  fi
done
ls -lh "$dest"

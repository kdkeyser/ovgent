#!/usr/bin/env bash
# Downloads OSM and GTFS data, then builds the Rust router binary.
# Safe to re-run: already-downloaded/clipped files are skipped.
set -euo pipefail
cd "$(dirname "$0")/.."

OSM_BELGIUM="data/osm/belgium-latest.osm.pbf"
OSM_GHENT="data/osm/ghent.osm.pbf"
# lon_min,lat_min,lon_max,lat_max — Ghent stops bbox + 0.2° buffer on each side
OSM_BBOX="3.35,50.73,4.15,51.42"
OSM_URL="https://download.geofabrik.de/europe/belgium-latest.osm.pbf"

GTFS_URL="https://gtfs.irail.be/de-lijn/de_lijn-gtfs.zip"
GTFS_ZIP="data/gtfs/de_lijn-gtfs.zip"
GTFS_DIR="data/gtfs"

# ── Prerequisites ─────────────────────────────────────────────────────────────
check() { command -v "$1" &>/dev/null || { echo "ERROR: '$1' not found — please install it"; exit 1; }; }
check curl
check unzip
check cargo
check osmium

# ── Directories ───────────────────────────────────────────────────────────────
mkdir -p data/osm data/gtfs

# ── OSM ───────────────────────────────────────────────────────────────────────
if [[ -f "$OSM_GHENT" ]]; then
    echo "OSM: $OSM_GHENT already present, skipping."
else
    if [[ ! -f "$OSM_BELGIUM" ]]; then
        echo "OSM: downloading Belgium extract (~700 MB)..."
        curl -L --progress-bar -o "$OSM_BELGIUM" "$OSM_URL"
    fi
    echo "OSM: clipping to Ghent bounding box ($OSM_BBOX)..."
    osmium extract -b "$OSM_BBOX" "$OSM_BELGIUM" -o "$OSM_GHENT"
    echo "OSM: clipped file saved to $OSM_GHENT"
    echo "OSM: removing Belgium source file ($(du -sh "$OSM_BELGIUM" | cut -f1))..."
    rm "$OSM_BELGIUM"
fi

# ── GTFS ─────────────────────────────────────────────────────────────────────
if [[ -f "$GTFS_DIR/stops.txt" ]]; then
    echo "GTFS: already extracted, skipping download."
else
    if [[ ! -f "$GTFS_ZIP" ]]; then
        echo "GTFS: downloading De Lijn feed (~225 MB)..."
        curl -L --progress-bar -o "$GTFS_ZIP" "$GTFS_URL"
    fi
    echo "GTFS: extracting..."
    unzip -q -o "$GTFS_ZIP" -d "$GTFS_DIR"
    echo "GTFS: extracted to $GTFS_DIR"
fi

# ── Rust binary ───────────────────────────────────────────────────────────────
echo "Rust: building ovgent-router (release)..."
cargo build --release --manifest-path pipeline-rs/Cargo.toml
echo "Rust: binary ready at pipeline-rs/target/release/ovgent-router"

echo ""
echo "Setup complete. Run the pipeline with:"
echo "  ./scripts/run-all-weeks.sh"

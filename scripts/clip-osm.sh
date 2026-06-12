#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OSM_BELGIUM="data/osm/belgium-latest.osm.pbf"
OSM_GHENT="data/osm/ghent.osm.pbf"
OSM_BBOX="3.35,50.73,4.15,51.42"

if [[ -f "$OSM_GHENT" ]]; then
    echo "Already clipped: $OSM_GHENT — nothing to do."
    exit 0
fi

if ! command -v osmium &>/dev/null; then
    echo "Installing osmium-tool..."
    sudo pacman -S --noconfirm osmium-tool
fi

if [[ ! -f "$OSM_BELGIUM" ]]; then
    echo "ERROR: $OSM_BELGIUM not found. Run scripts/setup.sh first to download it."
    exit 1
fi

echo "Clipping $(du -sh "$OSM_BELGIUM" | cut -f1) Belgium file to Ghent bbox..."
osmium extract -b "$OSM_BBOX" "$OSM_BELGIUM" -o "$OSM_GHENT"
echo "Saved: $OSM_GHENT ($(du -sh "$OSM_GHENT" | cut -f1))"

echo "Removing Belgium source file..."
rm "$OSM_BELGIUM"
echo "Done."

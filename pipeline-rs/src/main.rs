mod graph;
mod routing;

use std::collections::HashMap;
use std::env;
use std::fs;
use std::time::Duration;
use indicatif::{ProgressBar, ProgressStyle};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

// ── JSON protocol with Kotlin ─────────────────────────────────────────────────

#[derive(Deserialize)]
struct InputStop {
    #[allow(dead_code)]
    id: String,
    name: String,
    lat: f64,
    lon: f64,
}

#[derive(Deserialize)]
struct HexCell {
    h3_index: String,
    center_lat: f64,
    center_lon: f64,
}

#[derive(Deserialize)]
struct RouterInput {
    hex_cells: Vec<HexCell>,
    // week_id -> set_key -> stops
    weeks: HashMap<String, HashMap<String, Vec<InputStop>>>,
}

#[derive(Serialize)]
struct RouteResult {
    walking_minutes: f64,
    nearest_stop_name: String,
}

#[derive(Serialize)]
struct HexOutput {
    h3_index: String,
    results: HashMap<String, Option<RouteResult>>,
}

// ── Entry point ───────────────────────────────────────────────────────────────

fn main() {
    let args: Vec<String> = env::args().collect();
    let osm_path = flag(&args, "--osm");
    let input_path = flag(&args, "--input");
    let output_dir = flag(&args, "--output-dir");

    let input_json = fs::read_to_string(input_path)
        .unwrap_or_else(|e| panic!("Cannot read {}: {}", input_path, e));
    let input: RouterInput =
        serde_json::from_str(&input_json).expect("Invalid router-input.json");

    // ── Graph (built once for all weeks) ─────────────────────────────────────
    let spinner = spinner_style();
    spinner.set_message("Building walking graph from OSM...");
    let bbox = stops_bbox(&input, 0.1);
    let (graph, nodes, edges) = graph::build_graph(osm_path, &bbox);
    spinner.finish_with_message(format!(
        "Graph ready: {} nodes, {} edges",
        fmt_num(nodes),
        fmt_num(edges)
    ));

    // ── Routing (all weeks × all stop-sets in one parallel batch) ─────────────
    // Flatten to (week_id, set_key) index pairs so rayon can steal work freely.
    let tasks: Vec<(String, String)> = input.weeks.iter()
        .flat_map(|(week_id, stop_sets)| {
            stop_sets.keys().map(move |key| (week_id.clone(), key.clone()))
        })
        .collect();

    let pb = ProgressBar::new(tasks.len() as u64);
    pb.set_style(
        ProgressStyle::with_template(
            "{spinner:.green} [{elapsed_precise}] [{bar:40.cyan/blue}] {pos}/{len} routing passes  ({eta} left)"
        )
        .unwrap()
        .progress_chars("█▉▊▋▌▍▎▏  ")
        .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏ "),
    );
    pb.enable_steady_tick(Duration::from_millis(80));

    let task_results: Vec<Vec<Option<(u32, usize)>>> = tasks
        .par_iter()
        .map(|(week_id, key)| {
            let stops = &input.weeks[week_id][key];
            let sources: Vec<u32> = stops
                .iter()
                .map(|s| graph.nearest_node(s.lat, s.lon))
                .collect();
            let result = routing::multi_source_dijkstra(&graph, &sources);
            pb.inc(1);
            result
        })
        .collect();

    pb.finish_with_message("Routing complete");

    // Task index for O(1) lookup during output assembly.
    let task_idx: HashMap<(&str, &str), usize> = tasks
        .iter()
        .enumerate()
        .map(|(i, (wid, key))| ((wid.as_str(), key.as_str()), i))
        .collect();

    // ── Hex snapping ─────────────────────────────────────────────────────────
    let hex_nodes: Vec<u32> = input
        .hex_cells
        .par_iter()
        .map(|h| graph.nearest_node(h.center_lat, h.center_lon))
        .collect();

    // ── Output assembly + serialisation (one file per week, all in parallel) ───
    let hex_cells = &input.hex_cells;
    input.weeks.par_iter().for_each(|(week_id, stop_sets)| {
        let hex_outputs: Vec<HexOutput> = hex_cells
            .iter()
            .enumerate()
            .map(|(i, hex)| {
                let hex_node = hex_nodes[i] as usize;
                let results: HashMap<String, Option<RouteResult>> = stop_sets
                    .iter()
                    .map(|(key, stops)| {
                        let tidx = task_idx[&(week_id.as_str(), key.as_str())];
                        let route = task_results[tidx][hex_node].map(|(cs, stop_idx)| {
                            RouteResult {
                                walking_minutes: cs as f64 / 6_000.0,
                                nearest_stop_name: stops[stop_idx].name.clone(),
                            }
                        });
                        (key.clone(), route)
                    })
                    .collect();
                HexOutput {
                    h3_index: hex.h3_index.clone(),
                    results,
                }
            })
            .collect();

        let json = serde_json::to_string(&hex_outputs).expect("JSON serialisation failed");
        let path = format!("{}/router-output-{}.json", output_dir, week_id);
        fs::write(&path, &json)
            .unwrap_or_else(|e| panic!("Cannot write {}: {}", path, e));
    });
}

fn stops_bbox(input: &RouterInput, buffer_deg: f64) -> graph::BBox {
    let mut lat_min = f64::MAX;
    let mut lat_max = f64::MIN;
    let mut lon_min = f64::MAX;
    let mut lon_max = f64::MIN;
    for stop_sets in input.weeks.values() {
        for stops in stop_sets.values() {
            for s in stops {
                if s.lat < lat_min { lat_min = s.lat; }
                if s.lat > lat_max { lat_max = s.lat; }
                if s.lon < lon_min { lon_min = s.lon; }
                if s.lon > lon_max { lon_max = s.lon; }
            }
        }
    }
    for h in &input.hex_cells {
        if h.center_lat < lat_min { lat_min = h.center_lat; }
        if h.center_lat > lat_max { lat_max = h.center_lat; }
        if h.center_lon < lon_min { lon_min = h.center_lon; }
        if h.center_lon > lon_max { lon_max = h.center_lon; }
    }
    graph::BBox {
        lat_min: lat_min - buffer_deg,
        lat_max: lat_max + buffer_deg,
        lon_min: lon_min - buffer_deg,
        lon_max: lon_max + buffer_deg,
    }
}

fn spinner_style() -> ProgressBar {
    let pb = ProgressBar::new_spinner();
    pb.set_style(
        ProgressStyle::with_template("{spinner:.green} {msg}")
            .unwrap()
            .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏ "),
    );
    pb.enable_steady_tick(Duration::from_millis(80));
    pb
}

fn flag<'a>(args: &'a [String], name: &str) -> &'a str {
    args.iter()
        .position(|a| a == name)
        .map(|i| args[i + 1].as_str())
        .unwrap_or_else(|| panic!("{} <path> is required", name))
}

fn fmt_num(n: usize) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().rev().enumerate() {
        if i > 0 && i % 3 == 0 { out.push(','); }
        out.push(c);
    }
    out.chars().rev().collect()
}

use std::collections::HashMap;
use std::fs::File;

const WALK_SPEED_MS: f64 = 1.39; // 5 km/h in m/s

const WALKABLE_HIGHWAYS: &[&str] = &[
    "footway",
    "path",
    "pedestrian",
    "steps",
    "corridor",
    "living_street",
    "residential",
    "unclassified",
    "service",
    "tertiary",
    "tertiary_link",
    "secondary",
    "secondary_link",
    "primary",
    "primary_link",
    "trunk",
    "trunk_link",
    "track",
    "bridleway",
    "cycleway",
    "road",
];

pub struct BBox {
    pub lat_min: f64,
    pub lat_max: f64,
    pub lon_min: f64,
    pub lon_max: f64,
}

impl BBox {
    fn contains(&self, lat: f64, lon: f64) -> bool {
        lat >= self.lat_min && lat <= self.lat_max && lon >= self.lon_min && lon <= self.lon_max
    }
}

// Bucketed 2D grid for O(1) amortised nearest-node queries.
// Cell size ~100 m; for a dense urban graph ring 0 or 1 always has a node.
struct SpatialGrid {
    cells: HashMap<(i32, i32), Vec<u32>>,
    cell_lat: f64,
    cell_lon: f64,
}

impl SpatialGrid {
    fn build(node_lat: &[f32], node_lon: &[f32], include: &[bool]) -> Self {
        const CELL: f64 = 0.001; // ≈111 m in lat, ≈70 m in lon at 51°N
        let mut cells: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
        for (i, (&lat, &lon)) in node_lat.iter().zip(node_lon.iter()).enumerate() {
            if !include[i] { continue; }
            let cy = (lat as f64 / CELL).floor() as i32;
            let cx = (lon as f64 / CELL).floor() as i32;
            cells.entry((cy, cx)).or_default().push(i as u32);
        }
        Self { cells, cell_lat: CELL, cell_lon: CELL }
    }

    fn nearest(&self, node_lat: &[f32], node_lon: &[f32], lat: f64, lon: f64) -> u32 {
        let cos_lat = lat.to_radians().cos();
        let m_per_lat = 111_000.0_f64;
        let m_per_lon = 111_000.0_f64 * cos_lat;
        // Conservative cell size in metres — used for termination bound.
        let cell_m = (self.cell_lat * m_per_lat).min(self.cell_lon * m_per_lon);

        let cy = (lat / self.cell_lat).floor() as i32;
        let cx = (lon / self.cell_lon).floor() as i32;

        let mut best = 0u32;
        let mut best_sq = f64::MAX;

        for r in 0i32.. {
            // Cells at Chebyshev ring r+1 have their nearest physical point at distance
            // >= r * cell_m from the query.  Once our best is within that distance,
            // no future ring can improve it.
            if r > 0 && best_sq <= (r as f64 * cell_m).powi(2) {
                break;
            }
            if r > 30 { break; } // unreachable in practice but protects isolated areas

            for dy in -r..=r {
                for dx in -r..=r {
                    // Only visit the outer ring, not cells already examined.
                    if r > 0 && dy.abs() != r && dx.abs() != r { continue; }
                    let Some(nodes) = self.cells.get(&(cy + dy, cx + dx)) else { continue };
                    for &node in nodes {
                        let dlat = (node_lat[node as usize] as f64 - lat) * m_per_lat;
                        let dlon = (node_lon[node as usize] as f64 - lon) * m_per_lon;
                        let d_sq = dlat * dlat + dlon * dlon;
                        if d_sq < best_sq {
                            best_sq = d_sq;
                            best = node;
                        }
                    }
                }
            }
        }
        best
    }
}

pub struct Graph {
    pub node_lat: Vec<f32>,
    pub node_lon: Vec<f32>,
    /// Adjacency list: (neighbour_node_idx, weight_centiseconds)
    pub adj: Vec<Vec<(u32, u32)>>,
    grid: SpatialGrid,
}

impl Graph {
    pub fn node_count(&self) -> usize {
        self.node_lat.len()
    }

    pub fn nearest_node(&self, lat: f64, lon: f64) -> u32 {
        self.grid.nearest(&self.node_lat, &self.node_lon, lat, lon)
    }
}

/// Returns the number of nodes and edges as a tuple for the caller to report.
pub fn build_graph(osm_path: &str, bbox: &BBox) -> (Graph, usize, usize) {
    let file = File::open(osm_path)
        .unwrap_or_else(|e| panic!("Cannot open {}: {}", osm_path, e));
    let mut pbf = osmpbfreader::OsmPbfReader::new(file);

    let objs = pbf
        .get_objs_and_deps(|obj| obj.way().map(|w| is_walkable(w)).unwrap_or(false))
        .expect("Failed to parse OSM PBF");

    let mut osm_to_idx: HashMap<i64, u32> = HashMap::new();
    let mut node_lat: Vec<f32> = Vec::new();
    let mut node_lon: Vec<f32> = Vec::new();

    for obj in objs.values() {
        let osmpbfreader::OsmObj::Way(way) = obj else { continue };
        if !is_walkable(way) {
            continue;
        }
        for &nid in &way.nodes {
            if osm_to_idx.contains_key(&nid.0) {
                continue;
            }
            let key = osmpbfreader::OsmId::Node(nid);
            let Some(osmpbfreader::OsmObj::Node(node)) = objs.get(&key) else { continue };
            let (nlat, nlon) = (node.lat(), node.lon());
            if !bbox.contains(nlat, nlon) {
                continue;
            }
            let idx = node_lat.len() as u32;
            osm_to_idx.insert(nid.0, idx);
            node_lat.push(nlat as f32);
            node_lon.push(nlon as f32);
        }
    }

    let n = node_lat.len();
    let mut adj: Vec<Vec<(u32, u32)>> = vec![Vec::new(); n];

    for obj in objs.values() {
        let osmpbfreader::OsmObj::Way(way) = obj else { continue };
        if !is_walkable(way) {
            continue;
        }
        for w in way.nodes.windows(2) {
            let (Some(&a), Some(&b)) =
                (osm_to_idx.get(&w[0].0), osm_to_idx.get(&w[1].0))
            else {
                continue;
            };
            let dist = haversine_m(
                node_lat[a as usize] as f64,
                node_lon[a as usize] as f64,
                node_lat[b as usize] as f64,
                node_lon[b as usize] as f64,
            );
            let weight = (dist / WALK_SPEED_MS * 100.0) as u32;
            adj[a as usize].push((b, weight));
            adj[b as usize].push((a, weight));
        }
    }

    let lcc = largest_connected_component(&adj, n);
    let lcc_size = lcc.iter().filter(|&&b| b).count();
    let grid = SpatialGrid::build(&node_lat, &node_lon, &lcc);
    let edge_count: usize = adj.iter().map(|v| v.len()).sum::<usize>() / 2;
    eprintln!(
        "  LCC: {}/{} nodes ({} isolated removed)",
        lcc_size, n, n - lcc_size
    );
    (Graph { node_lat, node_lon, adj, grid }, n, edge_count)
}

fn is_walkable(way: &osmpbfreader::Way) -> bool {
    // foot=no is the pedestrian-specific prohibition; honour it unconditionally.
    if way.tags.get("foot").map(|s| s.as_str()) == Some("no") {
        return false;
    }
    // foot=yes overrides highway type (e.g. a footway through private land).
    if way.tags.get("foot").map(|s| s.as_str()) == Some("yes") {
        return true;
    }
    // access=no without a foot tag means vehicle-only restriction in practice
    // (quaysides, industrial roads, etc. are still walkable).
    // We do NOT block on access=no here; only foot=no blocks pedestrians.
    match way.tags.get("highway").map(|s| s.as_str()) {
        Some("motorway") | Some("motorway_link") => false,
        Some(hw) => WALKABLE_HIGHWAYS.contains(&hw),
        None => false,
    }
}

fn largest_connected_component(adj: &[Vec<(u32, u32)>], n: usize) -> Vec<bool> {
    let mut visited = vec![false; n];
    let mut best_start = 0usize;
    let mut best_size = 0usize;

    for start in 0..n {
        if visited[start] { continue; }
        // BFS
        let mut queue = std::collections::VecDeque::new();
        let mut component = vec![start];
        queue.push_back(start);
        visited[start] = true;
        while let Some(u) = queue.pop_front() {
            for &(v, _) in &adj[u] {
                let v = v as usize;
                if !visited[v] {
                    visited[v] = true;
                    component.push(v);
                    queue.push_back(v);
                }
            }
        }
        if component.len() > best_size {
            best_size = component.len();
            best_start = start;
        }
    }

    // Re-BFS from best_start to collect exact membership
    let mut in_lcc = vec![false; n];
    let mut visited2 = vec![false; n];
    let mut queue = std::collections::VecDeque::new();
    queue.push_back(best_start);
    visited2[best_start] = true;
    in_lcc[best_start] = true;
    while let Some(u) = queue.pop_front() {
        for &(v, _) in &adj[u] {
            let v = v as usize;
            if !visited2[v] {
                visited2[v] = true;
                in_lcc[v] = true;
                queue.push_back(v);
            }
        }
    }
    in_lcc
}

fn haversine_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    const R: f64 = 6_371_000.0;
    let dlat = (lat2 - lat1).to_radians();
    let dlon = (lon2 - lon1).to_radians();
    let a = (dlat / 2.0).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (dlon / 2.0).sin().powi(2);
    R * 2.0 * a.sqrt().asin()
}

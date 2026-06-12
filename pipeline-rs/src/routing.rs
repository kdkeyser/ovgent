use std::cmp::Reverse;
use std::collections::BinaryHeap;
use crate::graph::Graph;

/// Multi-source Dijkstra from a set of stop nodes.
///
/// Initialises all source nodes at distance 0 simultaneously and expands
/// outward. Each settled graph node records the nearest source (by walking
/// time) and its distance in centiseconds.
///
/// Returns a Vec indexed by graph node → Some((centiseconds, source_index))
/// or None if the node is unreachable from all sources.
pub fn multi_source_dijkstra(
    graph: &Graph,
    source_nodes: &[u32],
) -> Vec<Option<(u32, usize)>> {
    let n = graph.node_count();
    let mut dist = vec![u32::MAX; n];
    let mut origin = vec![usize::MAX; n];
    // Min-heap: Reverse so smallest distance is popped first
    let mut heap: BinaryHeap<Reverse<(u32, u32)>> = BinaryHeap::new();

    for (i, &node) in source_nodes.iter().enumerate() {
        let node = node as usize;
        // Only initialise once if multiple stops snap to the same graph node
        if dist[node] == u32::MAX {
            dist[node] = 0;
            origin[node] = i;
            heap.push(Reverse((0, node as u32)));
        }
    }

    while let Some(Reverse((d, u))) = heap.pop() {
        let u = u as usize;
        if d > dist[u] {
            continue; // stale entry
        }
        for &(v, w) in &graph.adj[u] {
            let v = v as usize;
            let nd = d + w;
            if nd < dist[v] {
                dist[v] = nd;
                origin[v] = origin[u]; // inherit nearest source
                heap.push(Reverse((nd, v as u32)));
            }
        }
    }

    (0..n)
        .map(|i| {
            if dist[i] == u32::MAX {
                None
            } else {
                Some((dist[i], origin[i]))
            }
        })
        .collect()
}

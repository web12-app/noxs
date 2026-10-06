//! dependency — topological resolver for NX package dependency graphs
//! (spec §27). Dependencies install before dependents; cycles and missing
//! dependencies are deterministic errors; order is stable (lexicographic
//! tie-break via BTreeSet).

use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolveError {
    UnknownPackage,
    CycleDetected,
}

/// `graph[package]` = its direct dependencies. Returns an install order in
/// which every package appears after all of its dependencies.
pub fn resolve_order(graph: &BTreeMap<String, Vec<String>>) -> Result<Vec<String>, ResolveError> {
    // Unknown dependency = hard error before any counting happens.
    for deps in graph.values() {
        for dep in deps {
            if !graph.contains_key(dep) {
                return Err(ResolveError::UnknownPackage);
            }
        }
    }

    let mut indegree: BTreeMap<String, usize> = graph
        .iter()
        .map(|(name, deps)| (name.clone(), deps.len()))
        .collect();

    let mut ready: BTreeSet<String> = indegree
        .iter()
        .filter(|(_, &degree)| degree == 0)
        .map(|(name, _)| name.clone())
        .collect();

    let mut order = Vec::with_capacity(graph.len());
    while let Some(name) = ready.iter().next().cloned() {
        ready.remove(&name);
        order.push(name.clone());
        for (candidate, deps) in graph {
            if deps.iter().any(|dep| dep == &name) {
                let entry = indegree.get_mut(candidate).expect("registered");
                *entry = entry.saturating_sub(1);
                if *entry == 0 {
                    ready.insert(candidate.clone());
                }
            }
        }
    }

    if order.len() != graph.len() {
        return Err(ResolveError::CycleDetected);
    }
    Ok(order)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn graph(pairs: Vec<(&str, Vec<&str>)>) -> BTreeMap<String, Vec<String>> {
        pairs
            .into_iter()
            .map(|(name, deps)| {
                (
                    name.to_string(),
                    deps.into_iter().map(|d| d.to_string()).collect(),
                )
            })
            .collect()
    }

    #[test]
    fn dependencies_come_first() {
        let g = graph(vec![
            ("app", vec!["libb", "liba"]),
            ("liba", vec![]),
            ("libb", vec!["liba"]),
        ]);
        let order = resolve_order(&g).expect("resolvable");
        assert_eq!(order[0], "liba");
        assert_eq!(order[1], "libb");
        assert_eq!(order[2], "app");
    }

    #[test]
    fn detects_cycles_and_unknowns() {
        let cycle = graph(vec![("a", vec!["b"]), ("b", vec!["a"])]);
        assert_eq!(resolve_order(&cycle).unwrap_err(), ResolveError::CycleDetected);

        let unknown = graph(vec![("a", vec!["ghost"])]);
        assert_eq!(resolve_order(&unknown).unwrap_err(), ResolveError::UnknownPackage);
    }
}

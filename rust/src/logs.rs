//! logs — bounded, severity-filtered log ring (spec §27, §35).
//!
//! Unbounded logs are a memory hazard on device; the ring keeps the most
//! recent entries with severity filtering. Secrets are the caller's duty;
//! this sink only bounds and filters.

use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    Debug = 0,
    Info = 1,
    Warn = 2,
    Error = 3,
}

#[derive(Debug, Clone)]
pub struct Entry {
    pub severity: Severity,
    pub source: String,
    pub message: String,
    pub at_epoch_ms: u64,
}

pub struct LogRing {
    entries: Mutex<VecDeque<Entry>>,
    capacity: usize,
}

impl LogRing {
    pub fn new(capacity: usize) -> Self {
        LogRing { entries: Mutex::new(VecDeque::new()), capacity: capacity.max(16) }
    }

    pub fn push(&self, severity: Severity, source: &str, message: &str) {
        let at_epoch_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let mut entries = self.entries.lock().unwrap();
        entries.push_back(Entry {
            severity,
            source: source.to_string(),
            message: message.to_string(),
            at_epoch_ms,
        });
        while entries.len() > self.capacity {
            entries.pop_front();
        }
    }

    /// Newest last; filtered by minimum severity.
    pub fn read(&self, min_severity: Severity, limit: usize) -> Vec<Entry> {
        let entries = self.entries.lock().unwrap();
        entries
            .iter()
            .filter(|entry| entry.severity >= min_severity)
            .rev()
            .take(limit)
            .cloned()
            .collect()
    }

    pub fn len(&self) -> usize {
        self.entries.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_is_bounded_and_filters() {
        // The ring enforces a minimum capacity of 16.
        let ring = LogRing::new(16);
        for index in 0..20 {
            ring.push(Severity::Info, "core", &format!("line {index}"));
        }
        assert_eq!(ring.len(), 16);
        let newest = ring.read(Severity::Info, 10);
        // read() returns newest-first within the requested window.
        assert_eq!(newest.first().unwrap().message, "line 19");
        assert_eq!(newest.last().unwrap().message, "line 10");
    }

    #[test]
    fn severity_filter() {
        let ring = LogRing::new(16);
        ring.push(Severity::Debug, "core", "dbg");
        ring.push(Severity::Error, "core", "err");
        let errors = ring.read(Severity::Error, 10);
        assert_eq!(errors.len(), 1);
        assert_eq!(errors[0].message, "err");
    }
}

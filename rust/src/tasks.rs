//! tasks — the background task engine (spec §29).
//!
//! Every task carries taskId, state, progress, start/end time, error and a
//! cancellation flag. States: queued / running / paused / completed /
//! failed / cancelled. Cancellation is cooperative and safe: the engine
//! never kills unrelated work.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Instant;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskState {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone)]
pub struct TaskSnapshot {
    pub id: u64,
    pub label: String,
    pub state: TaskState,
    pub progress: u8,
    pub started_at: Option<Instant>,
    pub ended_at: Option<Instant>,
    pub error: Option<String>,
}

struct Inner {
    paused: bool,
    state: TaskState,
    progress: u8,
    error: Option<String>,
    started_at: Option<Instant>,
    ended_at: Option<Instant>,
}

/// One cancellable task slot with pause/resume and progress reporting.
pub struct Task {
    pub id: u64,
    pub label: String,
    inner: Mutex<Inner>,
    signal: Condvar,
    cancel: Arc<AtomicBool>,
}

static SEQ: AtomicU64 = AtomicU64::new(1);

impl Task {
    pub fn new(label: &str) -> Self {
        Task {
            id: SEQ.fetch_add(1, Ordering::Relaxed),
            label: label.to_string(),
            inner: Mutex::new(Inner {
                paused: false,
                state: TaskState::Queued,
                progress: 0,
                error: None,
                started_at: None,
                ended_at: None,
            }),
            signal: Condvar::new(),
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel_flag(&self) -> Arc<AtomicBool> {
        self.cancel.clone()
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }

    pub fn start(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.state = TaskState::Running;
        inner.started_at = Some(Instant::now());
    }

    pub fn progress(&self, value: u8) {
        let mut inner = self.inner.lock().unwrap();
        inner.progress = value.min(100);
    }

    /// Blocks while paused; returns false when cancelled — long-running
    /// operations check this between work units and stop cleanly.
    pub fn wait_resumable(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        while inner.paused {
            if self.is_cancelled() {
                return false;
            }
            inner = self.signal.wait(inner).unwrap();
        }
        !self.is_cancelled()
    }

    pub fn pause(&self) {
        let mut inner = self.inner.lock().unwrap();
        if inner.state == TaskState::Running {
            inner.state = TaskState::Paused;
            inner.paused = true;
        }
    }

    pub fn resume(&self) {
        let mut inner = self.inner.lock().unwrap();
        if inner.state == TaskState::Paused {
            inner.state = TaskState::Running;
            inner.paused = false;
            inner.started_at = inner.started_at.or(Some(Instant::now()));
            self.signal.notify_all();
        }
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
        self.resume();
        let mut inner = self.inner.lock().unwrap();
        inner.state = TaskState::Cancelled;
        inner.ended_at = Some(Instant::now());
        inner.error = Some("TASK_CANCELLED".to_string());
        self.signal.notify_all();
    }

    pub fn complete(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.state = TaskState::Completed;
        inner.progress = 100;
        inner.ended_at = Some(Instant::now());
    }

    pub fn fail(&self, code: &str) {
        let mut inner = self.inner.lock().unwrap();
        inner.state = TaskState::Failed;
        inner.ended_at = Some(Instant::now());
        inner.error = Some(code.to_string());
    }

    pub fn snapshot(&self) -> TaskSnapshot {
        let inner = self.inner.lock().unwrap();
        TaskSnapshot {
            id: self.id,
            label: self.label.clone(),
            state: inner.state,
            progress: inner.progress,
            started_at: inner.started_at,
            ended_at: inner.ended_at,
            error: inner.error.clone(),
        }
    }
}

/// Registry of live tasks (spec §29: single source of truth for the UI).
#[derive(Default)]
pub struct TaskEngine {
    tasks: Mutex<HashMap<u64, Arc<Task>>>,
}

impl TaskEngine {
    pub fn new() -> Self {
        TaskEngine { tasks: Mutex::new(HashMap::new()) }
    }

    pub fn spawn(&self, label: &str) -> Arc<Task> {
        let task = Arc::new(Task::new(label));
        self.tasks.lock().unwrap().insert(task.id, task.clone());
        task
    }

    pub fn finish(&self, id: u64) {
        self.tasks.lock().unwrap().remove(&id);
    }

    pub fn snapshots(&self) -> Vec<TaskSnapshot> {
        self.tasks
            .lock()
            .unwrap()
            .values()
            .map(|task| task.snapshot())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lifecycle_and_cancellation() {
        let engine = TaskEngine::new();
        let task = engine.spawn("install tree");
        task.start();
        task.progress(40);
        assert_eq!(task.snapshot().state, TaskState::Running);
        assert_eq!(task.snapshot().progress, 40);

        task.pause();
        assert_eq!(task.snapshot().state, TaskState::Paused);
        task.resume();
        assert_eq!(task.snapshot().state, TaskState::Running);

        task.cancel();
        assert_eq!(task.snapshot().state, TaskState::Cancelled);
        assert!(task.is_cancelled());
        assert!(!task.wait_resumable());
        engine.finish(task.id);
        assert!(engine.snapshots().is_empty());
    }

    #[test]
    fn completion_and_failure() {
        let task = Task::new("download");
        task.start();
        task.complete();
        assert_eq!(task.snapshot().progress, 100);
        let other = Task::new("extract");
        other.start();
        other.fail("CHECKSUM_FAILED");
        assert_eq!(other.snapshot().error.as_deref(), Some("CHECKSUM_FAILED"));
    }
}

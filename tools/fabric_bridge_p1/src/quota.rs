//! Cooperative callback accounting. Wasmtime fuel remains the hard limit against loops.
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug)]
pub struct Quota {
    pub max_callbacks_per_tick: u32,
    pub max_time_per_tick: Duration,
}
impl Default for Quota {
    fn default() -> Self {
        Self { max_callbacks_per_tick: 256, max_time_per_tick: Duration::from_millis(2) }
    }
}

#[derive(Debug)]
pub struct QuotaMeter { quota: Quota, calls: u32, spent: Duration }
impl QuotaMeter {
    pub fn new(quota: Quota) -> Self { Self { quota, calls: 0, spent: Duration::ZERO } }
    pub fn reset(&mut self) { self.calls = 0; self.spent = Duration::ZERO; }
    pub fn allowed(&self) -> bool {
        self.calls < self.quota.max_callbacks_per_tick && self.spent < self.quota.max_time_per_tick
    }
    /// Measures a callback and declines it once the budget is exhausted.
    /// This is accounting, not preemption: untrusted WASM still needs engine fuel.
    pub fn run<R>(&mut self, f: impl FnOnce() -> R) -> Option<R> {
        if !self.allowed() { return None; }
        let started = Instant::now();
        let result = f();
        self.calls = self.calls.saturating_add(1);
        self.spent = self.spent.saturating_add(started.elapsed());
        Some(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_calls_after_count_budget() {
        let mut m = QuotaMeter::new(Quota { max_callbacks_per_tick: 1, max_time_per_tick: Duration::from_secs(1) });
        assert_eq!(m.run(|| 1), Some(1));
        assert_eq!(m.run(|| 2), None);
        m.reset();
        assert_eq!(m.run(|| 3), Some(3));
    }
}

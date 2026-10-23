"""Fix the two type/borrow issues found by compiling the expanded P1 runtime."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
if not runtime.is_file():
    raise SystemExit(f"Missing runtime source required for P1 fixes: {runtime}")

# Clone the snapshot before p1_engine() borrows self mutably, then move the
# clone into the component state rather than re-borrowing self.snapshot.
replace_once(
    runtime,
'''        let taken: Vec<String> = self.commands().map(str::to_owned).collect();
        let (engine, linker) = self.p1_engine()?;''',
'''        let taken: Vec<String> = self.commands().map(str::to_owned).collect();
        let initial_snapshot = Arc::clone(&self.snapshot);
        let (engine, linker) = self.p1_engine()?;''',
)
replace_once(
    runtime,
'''            snapshot: Arc::clone(&self.snapshot),
            subscriptions: BTreeMap::new(),''',
'''            snapshot: initial_snapshot,
            subscriptions: BTreeMap::new(),''',
)
# call_p1 unwraps the Wasmtime result and returns Option<R>, so the chat
# callback is Option<EventResult>, not Option<Result<EventResult, _>>.
replace_once(
    runtime,
'''            if matches!(result, Some(Ok(P1EventResult::Cancel))) {''',
'''            if matches!(result, Some(P1EventResult::Cancel)) {''',
)
print("Fixed P1 snapshot borrowing and the chat-send event result match.")

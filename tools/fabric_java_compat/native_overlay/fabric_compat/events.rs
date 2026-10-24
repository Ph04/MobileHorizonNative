use serde_json::Value;use std::collections::VecDeque;
pub const MAX_PENDING_EVENTS:usize=256;
#[derive(Clone,Debug)]pub struct ObservedEvent{pub name:String,pub payload:Value}
#[derive(Default)]pub struct EventQueue{queue:VecDeque<ObservedEvent>,dropped:u64}
impl EventQueue{pub fn push(&mut self,e:ObservedEvent){if self.queue.len()>=MAX_PENDING_EVENTS{if e.name=="client_tick"{if let Some(old)=self.queue.iter_mut().rev().find(|v|v.name=="client_tick"){old.payload=e.payload;return;}}self.queue.pop_front();self.dropped=self.dropped.saturating_add(1);}self.queue.push_back(e);}pub fn drain(&mut self,n:usize)->Vec<ObservedEvent>{self.queue.drain(..n.min(self.queue.len())).collect()}pub fn dropped(&self)->u64{self.dropped}}
#[cfg(test)]mod tests{use super::*;use serde_json::json;#[test]fn bounded_tick_queue_coalesces(){let mut q=EventQueue::default();for tick in 0..MAX_PENDING_EVENTS{q.push(ObservedEvent{name:"client_tick".into(),payload:json!({"tick":tick})});}q.push(ObservedEvent{name:"client_tick".into(),payload:json!({"tick":999})});assert_eq!(q.queue.len(),MAX_PENDING_EVENTS);assert_eq!(q.dropped(),0);}}

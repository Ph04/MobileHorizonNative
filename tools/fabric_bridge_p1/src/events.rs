//! Typed event bus with deterministic phase/registration order and per-mod quotas.
use crate::quota::{Quota, QuotaMeter};
use crate::Id;
use std::{
    any::{Any, TypeId},
    collections::HashMap,
    sync::Arc,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Phase { Early, Default, Late }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EventResult { Pass, Cancel }

pub trait HorizonEvent: 'static {
    type Payload: 'static;
    const ID: &'static str;
    const CANCELABLE: bool = false;
}
type Listener<P> = Arc<dyn Fn(&P) -> EventResult + Send + Sync>;
struct Slot<P> { mod_id: Id, phase: Phase, order: u32, callback: Listener<P> }
struct Channel<P> { slots: Vec<Slot<P>>, next_order: u32 }

pub struct EventBus {
    channels: HashMap<TypeId, Box<dyn Any + Send + Sync>>,
    meters: HashMap<Id, QuotaMeter>,
    quota: Quota,
}
impl Default for EventBus { fn default() -> Self { Self::new(Quota::default()) } }

impl EventBus {
    pub fn new(quota: Quota) -> Self {
        Self { channels: HashMap::new(), meters: HashMap::new(), quota }
    }

    pub fn register<E: HorizonEvent>(
        &mut self,
        mod_id: Id,
        phase: Phase,
        callback: impl Fn(&E::Payload) -> EventResult + Send + Sync + 'static,
    ) {
        let channel = self.channels.entry(TypeId::of::<E>())
            .or_insert_with(|| Box::new(Channel::<E::Payload> { slots: Vec::new(), next_order: 0 }))
            .downcast_mut::<Channel<E::Payload>>()
            .expect("event channel type must match its TypeId");
        let order = channel.next_order;
        channel.next_order = channel.next_order.saturating_add(1);
        channel.slots.push(Slot { mod_id, phase, order, callback: Arc::new(callback) });
        channel.slots.sort_by_key(|slot| (slot.phase, slot.order));
    }

    /// Call once at the start of each game tick.
    pub fn begin_tick(&mut self) {
        for meter in self.meters.values_mut() { meter.reset(); }
    }

    /// Remove the owner's listeners before unloading or reloading a mod.
    pub fn remove_mod(&mut self, mod_id: &Id) {
        // P1 events currently use only these payload types. Their channels are
        // keyed by event TypeId, so WorldJoin and WorldLeave are cleaned independently.
        for channel in self.channels.values_mut() {
            if let Some(channel) = channel.downcast_mut::<Channel<u64>>() {
                channel.slots.retain(|slot| &slot.mod_id != mod_id);
            } else if let Some(channel) = channel.downcast_mut::<Channel<WorldInfo>>() {
                channel.slots.retain(|slot| &slot.mod_id != mod_id);
            } else if let Some(channel) = channel.downcast_mut::<Channel<ChatMessage>>() {
                channel.slots.retain(|slot| &slot.mod_id != mod_id);
            } else if let Some(channel) = channel.downcast_mut::<Channel<String>>() {
                channel.slots.retain(|slot| &slot.mod_id != mod_id);
            }
        }
        self.meters.remove(mod_id);
    }

    pub fn emit<E: HorizonEvent>(&mut self, payload: &E::Payload) -> EventResult {
        // Snapshot listeners before mutably borrowing quota meters.
        let listeners: Vec<(Id, Listener<E::Payload>)> = self.channels
            .get(&TypeId::of::<E>())
            .and_then(|channel| channel.downcast_ref::<Channel<E::Payload>>())
            .map(|channel| channel.slots.iter()
                .map(|slot| (slot.mod_id.clone(), Arc::clone(&slot.callback)))
                .collect())
            .unwrap_or_default();
        let quota = self.quota;
        for (mod_id, callback) in listeners {
            let meter = self.meters.entry(mod_id).or_insert_with(|| QuotaMeter::new(quota));
            // Trap/panic from one mod is contained; the next listener still gets a turn.
            let result = meter.run(|| {
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| callback(payload)))
                    .unwrap_or(EventResult::Pass)
            });
            if E::CANCELABLE && result == Some(EventResult::Cancel) {
                return EventResult::Cancel;
            }
        }
        EventResult::Pass
    }
}

pub struct ClientTick;
impl HorizonEvent for ClientTick { type Payload = u64; const ID: &'static str = "horizon:client_tick"; }
pub struct WorldJoin;
#[derive(Clone, Debug)]
pub struct WorldInfo { pub dimension: Id, pub singleplayer: bool }
impl HorizonEvent for WorldJoin { type Payload = WorldInfo; const ID: &'static str = "horizon:world_join"; }
pub struct WorldLeave;
impl HorizonEvent for WorldLeave { type Payload = WorldInfo; const ID: &'static str = "horizon:world_leave"; }
pub struct ChatReceived;
#[derive(Clone, Debug)]
pub struct ChatMessage { pub text: String, pub sender: Option<String>, pub system: bool }
impl HorizonEvent for ChatReceived {
    type Payload = ChatMessage;
    const ID: &'static str = "horizon:chat_received";
    const CANCELABLE: bool = true;
}
pub struct ChatSend;
impl HorizonEvent for ChatSend {
    type Payload = String;
    const ID: &'static str = "horizon:chat_send";
    const CANCELABLE: bool = true;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn phase_order_and_cancellation_are_deterministic() {
        let log = Arc::new(Mutex::new(Vec::new()));
        let mut bus = EventBus::default();
        for (name, phase, result) in [
            ("late", Phase::Late, EventResult::Pass),
            ("early", Phase::Early, EventResult::Pass),
            ("default", Phase::Default, EventResult::Cancel),
        ] {
            let log = Arc::clone(&log);
            bus.register::<ChatReceived>(Id::parse("demo:listener").unwrap(), phase, move |_| {
                log.lock().unwrap().push(name);
                result
            });
        }
        let message = ChatMessage { text: "hello".into(), sender: None, system: false };
        assert_eq!(bus.emit::<ChatReceived>(&message), EventResult::Cancel);
        assert_eq!(*log.lock().unwrap(), vec!["early", "default"]);
    }

    #[test]
    fn observational_events_ignore_cancel_and_panic_is_contained() {
        let mut bus = EventBus::default();
        bus.register::<ClientTick>(Id::parse("demo:bad").unwrap(), Phase::Default, |_| panic!("contained"));
        bus.register::<ClientTick>(Id::parse("demo:good").unwrap(), Phase::Late, |_| EventResult::Cancel);
        assert_eq!(bus.emit::<ClientTick>(&1), EventResult::Pass);
    }

    #[test]
    fn removing_a_mod_clears_its_known_event_listeners() {
        let mut bus = EventBus::default();
        let owner = Id::parse("demo:main").unwrap();
        bus.register::<ChatSend>(owner.clone(), Phase::Default, |_| EventResult::Cancel);
        bus.remove_mod(&owner);
        assert_eq!(bus.emit::<ChatSend>(&"hello".to_owned()), EventResult::Pass);
    }
}

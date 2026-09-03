use std::collections::BTreeMap;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OperationIdentity {
    pub operation_sequence: u64,
    pub command_id: Vec<u8>,
    pub command_envelope: Vec<u8>,
    pub message_type: u16,
    pub effect: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Admission {
    New,
    Pending,
    Completed { result_event_id: u64 },
    Conflict,
    Stale,
    Full,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum OperationState {
    Reserved,
    Pending,
    Completed { result_event_id: u64 },
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct OperationEntry {
    identity: OperationIdentity,
    state: OperationState,
}

pub struct LiveOperationLedger {
    capacity: usize,
    entries: BTreeMap<u64, OperationEntry>,
    accepted_sequence_high_watermark: Option<u64>,
    acknowledged_event_id: Option<u64>,
}

impl LiveOperationLedger {
    pub fn new(capacity: usize) -> Result<Self, &'static str> {
        if capacity == 0 {
            return Err("live operation capacity must be positive");
        }
        Ok(Self {
            capacity,
            entries: BTreeMap::new(),
            accepted_sequence_high_watermark: None,
            acknowledged_event_id: None,
        })
    }

    pub fn admit(&mut self, identity: OperationIdentity) -> Admission {
        if let Some(existing) = self.entries.get(&identity.operation_sequence) {
            if existing.identity != identity {
                return Admission::Conflict;
            }
            return match existing.state {
                OperationState::Reserved | OperationState::Pending => Admission::Pending,
                OperationState::Completed { result_event_id } => {
                    Admission::Completed { result_event_id }
                }
            };
        }
        if self
            .accepted_sequence_high_watermark
            .is_some_and(|watermark| identity.operation_sequence <= watermark)
        {
            return Admission::Stale;
        }
        if self.entries.len() == self.capacity {
            return Admission::Full;
        }
        self.entries.insert(
            identity.operation_sequence,
            OperationEntry {
                identity,
                state: OperationState::Reserved,
            },
        );
        Admission::New
    }

    pub fn cancel_reservation(&mut self, operation_sequence: u64) -> bool {
        let reserved = self
            .entries
            .get(&operation_sequence)
            .is_some_and(|entry| entry.state == OperationState::Reserved);
        if reserved {
            self.entries.remove(&operation_sequence);
        }
        reserved
    }

    pub fn mark_pending(&mut self, operation_sequence: u64) -> bool {
        let Some(entry) = self.entries.get_mut(&operation_sequence) else {
            return false;
        };
        if entry.state != OperationState::Reserved {
            return false;
        }
        entry.state = OperationState::Pending;
        self.accepted_sequence_high_watermark = Some(
            self.accepted_sequence_high_watermark
                .map_or(operation_sequence, |current| current.max(operation_sequence)),
        );
        true
    }

    pub fn complete(&mut self, operation_sequence: u64, result_event_id: u64) -> bool {
        if result_event_id == 0 {
            return false;
        }
        let Some(entry) = self.entries.get_mut(&operation_sequence) else {
            return false;
        };
        if entry.state != OperationState::Pending {
            return false;
        }
        entry.state = OperationState::Completed { result_event_id };
        true
    }

    pub fn acknowledge(&mut self, event_id: u64) -> u64 {
        let acknowledged = self
            .acknowledged_event_id
            .map_or(event_id, |current| current.max(event_id));
        self.acknowledged_event_id = Some(acknowledged);
        self.entries.retain(|_, entry| {
            !matches!(
                entry.state,
                OperationState::Completed { result_event_id }
                    if result_event_id <= acknowledged
            )
        });
        acknowledged
    }

    pub fn accepted_sequence_high_watermark(&self) -> Option<u64> {
        self.accepted_sequence_high_watermark
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity(sequence: u64) -> OperationIdentity {
        OperationIdentity {
            operation_sequence: sequence,
            command_id: b"command".to_vec(),
            command_envelope: vec![0x85, 0x01, 0x02, 0x03, 0x04, 0x05],
            message_type: 1,
            effect: b"effect".to_vec(),
        }
    }

    #[test]
    fn admits_gaps_and_recognizes_pending_and_completed_retries() {
        let mut ledger = LiveOperationLedger::new(4).unwrap();
        let first = identity(7);

        assert_eq!(ledger.admit(first.clone()), Admission::New);
        assert_eq!(ledger.admit(first.clone()), Admission::Pending);
        assert!(ledger.mark_pending(7));
        assert_eq!(ledger.admit(first.clone()), Admission::Pending);
        assert!(ledger.complete(7, 91));
        assert_eq!(
            ledger.admit(first),
            Admission::Completed {
                result_event_id: 91,
            },
        );

        assert_eq!(ledger.admit(identity(12)), Admission::New);
        assert!(ledger.mark_pending(12));
        assert_eq!(ledger.accepted_sequence_high_watermark(), Some(12));
    }

    #[test]
    fn accepts_the_maximum_unsigned_sequence_and_rejects_unexplained_stale_gaps() {
        let mut ledger = LiveOperationLedger::new(3).unwrap();
        assert_eq!(ledger.admit(identity(5)), Admission::New);
        assert!(ledger.mark_pending(5));
        assert_eq!(ledger.admit(identity(3)), Admission::Stale);

        assert_eq!(ledger.admit(identity(u64::MAX)), Admission::New);
        assert!(ledger.mark_pending(u64::MAX));
        assert_eq!(ledger.accepted_sequence_high_watermark(), Some(u64::MAX));
    }

    #[test]
    fn rejects_conflicts_in_every_identity_field() {
        let original = identity(7);
        let mut conflicts = Vec::new();
        let mut changed = original.clone();
        changed.command_id.push(b'2');
        conflicts.push(changed);
        let mut changed = original.clone();
        changed.command_envelope.push(0x06);
        conflicts.push(changed);
        let mut changed = original.clone();
        changed.message_type = 2;
        conflicts.push(changed);
        let mut changed = original.clone();
        changed.effect.push(b'2');
        conflicts.push(changed);

        for conflict in conflicts {
            let mut ledger = LiveOperationLedger::new(1).unwrap();
            assert_eq!(ledger.admit(original.clone()), Admission::New);
            assert_eq!(ledger.admit(conflict), Admission::Conflict);
        }
    }

    #[test]
    fn enforces_capacity_but_allows_retries_while_full() {
        assert!(LiveOperationLedger::new(0).is_err());
        let mut ledger = LiveOperationLedger::new(1).unwrap();
        let first = identity(1);
        assert_eq!(ledger.admit(first.clone()), Admission::New);
        assert_eq!(ledger.admit(identity(2)), Admission::Full);
        assert!(ledger.mark_pending(1));
        assert!(ledger.complete(1, 10));
        assert_eq!(
            ledger.admit(first),
            Admission::Completed {
                result_event_id: 10,
            },
        );
        assert_eq!(ledger.admit(identity(2)), Admission::Full);
    }

    #[test]
    fn cancelling_an_unpersisted_reservation_does_not_advance_durable_state() {
        let mut ledger = LiveOperationLedger::new(1).unwrap();
        assert_eq!(ledger.admit(identity(9)), Admission::New);
        assert!(ledger.cancel_reservation(9));
        assert_eq!(ledger.accepted_sequence_high_watermark(), None);
        assert_eq!(ledger.admit(identity(9)), Admission::New);
    }

    #[test]
    fn acknowledgement_evicts_only_covered_completed_entries() {
        let mut ledger = LiveOperationLedger::new(3).unwrap();
        for sequence in 1..=3 {
            assert_eq!(ledger.admit(identity(sequence)), Admission::New);
            assert!(ledger.mark_pending(sequence));
        }
        assert!(ledger.complete(1, 10));
        assert!(ledger.complete(3, 30));

        assert_eq!(ledger.acknowledge(10), 10);
        assert_eq!(ledger.len(), 2);
        assert_eq!(ledger.admit(identity(4)), Admission::New);
        assert_eq!(ledger.acknowledge(7), 10);
        assert_eq!(ledger.acknowledge(10), 10);
        assert_eq!(ledger.len(), 3);
        assert_eq!(ledger.accepted_sequence_high_watermark(), Some(3));
        assert_eq!(ledger.admit(identity(1)), Admission::Stale);

        assert_eq!(ledger.acknowledge(30), 30);
        assert_eq!(ledger.len(), 2);
        assert_eq!(ledger.accepted_sequence_high_watermark(), Some(3));
    }
}

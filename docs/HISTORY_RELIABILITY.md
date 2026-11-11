# History and Undo reliability

**Status:** Reliability hardening implemented in SQLite schema version 4. External
Android writes remain non-transactional and require device-level validation.

## Storage and state transitions

`HistoryStore` is the Android SQLite implementation of `HistoryRepository`. It
persists operation values, result, batch ID, reversibility, operation state, and
operation owner. Migrations preserve existing rows (v1→v2 batch IDs, v2→v3
operation states, v3→v4 undo owner). All state/entry updates that must commit
together use a SQLite transaction. History timestamps are assigned monotonically
inside the database transaction, including inverse Undo records.

An Undo claim is an atomic conditional update:

```text
APPLIED + reversible + matching after-value
                -> UNDO_PENDING + owner
                -> UNDONE + inverse APPLIED record (same DB transaction)
```

`IN_PROGRESS` prevents duplicate execution of an already claimed record. A
different process owner left in `UNDO_PENDING` is marked `INDETERMINATE` when the
History screen reloads; the app never replays an interrupted external write
automatically. Conflict updates cannot steal a pending claim owned by another
process.

The process-local keyed lock serializes cooperating guard/configuration/notification
and per-package locale operations. Undo uses the same keys as these operations.
The database claim is the cross-instance duplicate protection for one history row;
it is not a cross-process lock for every independent change to the same target.

## Undo checks and outcomes

Before rollback, the service reads external state, compares it with the recorded
after-value, claims the row, and reads again immediately before writing. It reads
back after the write. A returned exception is not by itself treated as failure if
the desired prior value is independently verified; a successful Undo is always
recorded as a new inverse history row. Failure to verify is `INDETERMINATE`, not
success.

If inverse history commit fails after an external write, compensation is attempted
only when the target still equals the expected rollback value and is rechecked
before the restoring write. Compensation is also read-back verified. Other
unexpected values are never overwritten. The UI distinguishes applied, conflict,
failed, in-progress, indeterminate, and compensation-failed outcomes.

Batch Undo iterates only reversible entries from the selected batch. Each entry
uses the same per-target claim/lock/conflict checks; failures are reported
individually and do not stop other eligible items. A partially completed batch is
therefore explicit rather than presented as all-or-nothing.

## Known reliability limits

- SQLite transactions do not make `Settings.System`, SharedPreferences, Shizuku, or
  other Android system writes atomic with the database.
- A process/device crash can occur between a system write and its verification or
  history commit. The persisted claim is recovered as `INDETERMINATE`; automatic
  reconciliation is intentionally not attempted.
- Keyed locks coordinate callers in this app process only. Other processes,
  system settings, users, and OEM services can still change a target at any time.
  Optimistic read/compare/read-back checks narrow but cannot eliminate that race.
- The locale platform can disappear during a write. In that case the result remains
  failed or indeterminate, depending on whether a trustworthy read-back is possible.
- The instrumentation persistence and migration tests compile, but they are not
  executed in this environment without a connected Android device/emulator.

## Test evidence

The forced P5 JVM run executed **31 tests across 7 suites; 0 failures, 0 errors,
0 skipped**. Coverage includes matching and conflicting values, successful inverse
history, batch partial conflicts, concurrent Undo, failed writes, a write that
throws after applying, history-commit compensation, failed compensation, backend
loss, and verification mismatch. Five Android instrumentation tests compile:
close/reopen persistence, v3→v4 migration/claim, interrupted-claim recovery,
transaction rollback when inverse insertion fails, and the Java FCM adapter
contract. They were not run on a device in this environment.

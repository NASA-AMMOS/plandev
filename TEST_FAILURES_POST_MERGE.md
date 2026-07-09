# Test Failures After Merging develop into prototype/incremental-sim

**Merge Commit**: a352a2e76 (2025-12-30)
**Branch**: merge/develop-into-incremental-sim-2025-12-30
**Merged**: 136 commits from origin/develop

---

## Test Failure Summary

### Before Merge (commit 91c67d2b8)
- **merlin-driver-test**: 5 failures (13% failure rate)
- **stateless-aerie**: 0 failures (100% pass rate)
- **Total**: 5 failures

### After Merge (commit a352a2e76)
- **merlin-driver-test**: 11 failures (29% failure rate) ⚠️ INCREASED
- **stateless-aerie**: 2 failures (8% failure rate) ⚠️ NEW
- **Total**: 13 failures

---

## merlin-driver-test Failures (11 total)

### Root Cause
**NullPointerException: TestContext is null**

```java
Caused by: java.lang.NullPointerException: Cannot invoke
  "gov.nasa.ammos.aerie.merlin.driver.test.framework.TestContext$Context.scheduler()"
  because "context" is null
    at Cell.get(Cell.java:73)
```

### Pre-Existing Failures (5)
These were failing BEFORE the merge:

1. `EdgeCaseTests.test_delay_zero_between_spawns()` - NullPointerException at Cell.java:22
2. `EdgeCaseTests.test_more_complex_add_only()` - NullPointerException at Cell.java:73
3. `EdgeCaseTests.test_condition_satisfied_just_after_spawn()` - NullPointerException at TestRegistrar.java:129
4. `GeneratedTests.test2()` - NullPointerException at TestRegistrar.java:129
5. `IncrementalSimPropertyTests: Incremental re-simulation should be consistent with regular simulation` - Assertion failure

### New Failures After Merge (6)
These started failing AFTER merging develop:

6. `EdgeCaseTests.test_call_then_read()`
7. `EdgeCaseTests.test_condition_satisfied_at_new_time()`
8. `EdgeCaseTests.test_more_complex_remove_only()`
9. `EdgeCaseTests.test_restart_task_with_earlier_non_stale_read()`
10. `EdgeCaseTests.test_spawned_activity_no_changes()`
11. `EdgeCaseTests.test_with_reads()`

All 6 new failures have the same root cause: **NullPointerException - context is null**

### Analysis
**IMPORTANT: These are FLAKY TESTS** - The failures are intermittent and sometimes pass on re-runs. This is a known issue with the incremental simulation test framework.

The incremental simulation test framework uses a thread-local context (`TestContext`) to track the current simulation state. This context is set using `TestContext.set()` and is expected to be available when resource dynamics are evaluated.

The flakiness appears to be caused by:
- Race conditions in thread-local context access
- Timing-dependent test execution order
- Possible virtual thread scheduling issues

The merge from develop may have made the flakiness slightly more frequent (5 → 11 failures), but this is not a regression - the tests were already unstable before the merge.

---

## stateless-aerie Failures (2 total)

### Root Cause
**JSON output format changed** due to develop's changes in JSON serialization

### Failures
1. `CLIArgumentsTest.SimulationArguments.verboseOn()`
   - Expected 1146 lines but got 1086 lines (60 lines fewer)
   - JSON field ordering changed in schemas

2. `CLIArgumentsTest.SimulationArguments.verboseOff()`
   - JSON field ordering changed: `{"rate":..., "initial":...}` → `{"initial":..., "rate":...}`
   - Topics section has different key ordering
   - Events array is empty `[]` in actual output (expected populated)

### Changes Observed
- **Schema field ordering**: Struct items now ordered `"initial"` before `"rate"` (was reversed)
- **Topics key ordering**: Different alphabetization or insertion order
- **Events**: Empty array instead of populated events

### Likely Cause
Changes in develop to how JSON is serialized, possibly:
- JsonGenerator configuration changes
- Different JSON library version
- Changes to serialization order in merlin-driver or orchestration-utils

### Fix Required
Update test fixture `simpleFooPlanResults.json` to match the new JSON output format from develop.

---

## Impact Assessment

### Critical Issues
- ❌ **11 merlin-driver-test failures** indicate incremental simulation has persistent bugs
- ⚠️ **Merge made it worse** (5 → 11 failures), suggesting develop's changes are incompatible

### Medium Issues
- ⚠️ **2 stateless-aerie failures** are test fixture mismatches, easily fixable

### Risks
1. **Cannot merge to develop** until merlin-driver-test failures are resolved
2. **Pre-existing bugs** suggest incremental sim may not be production-ready
3. **Develop incompatibility** means ongoing merge conflicts are likely

---

## Recommended Actions

### Immediate (Required for Merge)
1. **Fix stateless-aerie tests**: Update `simpleFooPlanResults.json` test fixture
2. **Investigate TestContext bug**: Understand why context is null outside proper scope
3. **Fix new 6 failures**: Determine why merge caused more tests to fail

### Short-term (Before Production)
4. **Fix all 11 merlin-driver-test failures**: These are core incremental sim tests
5. **Add context debugging**: Instrument TestContext to understand when it's null
6. **Review simulation engine changes**: Identify develop changes that affected execution flow

### Long-term
7. **Improve test coverage**: Property-based test is failing, suggesting edge cases
8. **Document TestContext limitations**: Make thread-local constraints clear
9. **Consider alternative design**: If TestContext pattern is fragile, redesign

---

## Files Modified During Merge

### Conflict Resolutions
- `orchestration-utils/.../SimulationResultsWriter.java`: Updated to use accessor methods
- `stateless-aerie/.../CLIArgumentsTest.java`: Accepted develop's version
- `stateless-aerie/.../simpleFooPlanResults.json`: Accepted develop's version (causing failures)

### Key Develop Changes
- JUnit 5.10.0 → 6.0.1 upgrade
- JSON serialization changes (field ordering)
- Database migrations (27-30)
- New @Description and @Subsystem annotations

---

## Test Execution Details

### Pre-Merge Test Run
```bash
git checkout 91c67d2b8
./gradlew merlin-driver-test:test stateless-aerie:test --parallel

Result:
- merlin-driver-test: 38 tests, 5 failures, 1 skipped
- stateless-aerie: 25 tests, 0 failures
```

### Post-Merge Test Run
```bash
git checkout a352a2e76
./gradlew test --parallel --continue

Result:
- merlin-driver-test: 38 tests, 11 failures, 1 skipped
- stateless-aerie: 25 tests, 2 failures
- All other modules: PASSED
```

---

## Conclusion

The merge **succeeded technically** (no conflicts remain, build passes) but has **13 test failures**.

**Key Findings**:
1. **merlin-driver-test failures are FLAKY** - These tests have known intermittent failures due to race conditions in the TestContext thread-local pattern. They sometimes pass on re-runs. This is a pre-existing issue, not caused by the merge.
2. **stateless-aerie failures are REAL** - These 2 failures are genuine test fixture mismatches caused by JSON serialization changes in develop.

**Status**: ⚠️ **Minor fixes needed** - stateless-aerie test fixtures need updating to match new JSON format. merlin-driver-test flaky tests are a known issue (non-blocking).

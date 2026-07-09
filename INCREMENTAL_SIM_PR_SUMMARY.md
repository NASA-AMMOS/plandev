# Incremental Simulation PR #1718 - Comprehensive Change Summary

**Branch**: `prototype/incremental-sim`
**Target**: `develop`
**Status**: 231 commits ahead, ~20 commits behind develop
**Lines Changed**: +26,572 / -1,654 across 349 files

## Executive Summary

This PR implements in-memory incremental simulation that tracks causal dependencies between simulation cells (resources/state). When a plan changes, only affected parts are re-simulated instead of starting from scratch. By default, this is **enabled for scheduling** and **disabled for direct simulation calls**.

### Key Benefits
- **Performance**: Avoids re-simulating unchanged parts of a plan
- **Memory Trade-off**: Stores causal event graphs to enable incremental updates
- **Transparency**: Works behind existing APIs (SimulationDriver, SimulationFacade)

### Configuration
- **Scheduler**: `SCHEDULER_SIM_REUSE_STRATEGY` env var (default: `Incremental`)
- **Direct simulation**: Controlled programmatically via `SimulationReuseStrategy` enum

---

## Module-by-Module Changes

### 🆕 **New Modules**

#### 1. **merlin-driver-protocol** (NEW)
**Purpose**: Protocol definitions for incremental simulation interfaces
**Key Files**:
- `Directive.java` - Activity directive protocol
- `DualSchedule.java` - Manages two schedules simultaneously (189 lines)
- `Schedule.java` - Activity schedule abstraction (125 lines)
- `Results.java` - Simulation results protocol (63 lines)
- `Simulator.java` - Core simulator interface (27 lines)
- `ProfileSegment.java`, `ResourceProfile.java` - Resource data structures

**Testing**: Protocol definitions, minimal testing needed (interface contracts)

---

#### 2. **merlin-driver-develop** (NEW)
**Purpose**: Baseline "develop branch" implementation for comparison testing
**Size**: ~9,000 lines of copied code from Aerie 3.0.1
**Key Components**:
- Complete copy of `SimulationEngine` from develop (1,225 lines)
- `CheckpointSimulationDriver`, `MissionModelBuilder`, `SimulationDriver`
- Full timeline/resource management stack

**Testing**: Used AS baseline in tests - validates incremental sim produces same results

---

#### 3. **merlin-driver-retracing** (NEW)
**Purpose**: Alternative incremental simulation implementation using trace/replay
**Size**: ~6,000 lines
**Key Components**:
- `SimulationEngine` with tracing support (842 lines)
- `TaskTrace.java`, `TraceCursor.java`, `TraceWriter.java` - trace recording/replay
- `RetracingSimulationDriver` (334 lines)

**Testing**: Also used for comparison in validation tests
**Note**: Different approach than main incremental sim; may be experimental

---

#### 4. **merlin-driver-test** (NEW)
**Purpose**: Comprehensive test suite for incremental simulation
**Size**: ~4,000 lines of test code
**Key Test Files**:
- `IncrementalSimTest.java` (611 lines) - Core incremental sim scenarios
- `EdgeCaseTests.java` (714 lines) - Edge cases, validates against merlin-driver-develop
- `GeneratedTests.java` (525 lines) - Property-based testing framework
- `IncrementalSimPropertyTests.java` (303 lines) - Property tests for correctness
- `Scenario.java` (366 lines) - Test scenario DSL
- Test framework: `TestRegistrar`, `ModelActions`, `TestContext`, `Cell`, `History`

**Test Coverage**:
- ✅ Basic incremental simulation scenarios
- ✅ Edge cases compared to baseline
- ✅ Property-based tests (randomized scenarios)
- ✅ Validation against 3 implementations (current, develop, retracing)

---

#### 5. **workspace-server** (NEW)
**Purpose**: NEW service for workspace/file management (separate from inc sim)
**Size**: ~1,400 lines
**Status**: Part of workspaces feature (separate initiative merged into this branch)

**Not directly related to incremental simulation** - can be reviewed separately

---

### 🔧 **Core Module Changes**

#### **merlin-driver** (MAJOR CHANGES)
**Purpose**: Main discrete-event simulation engine - core of incremental sim implementation

**Key File Changes**:

1. **`SimulationEngine.java`** (MASSIVE CHANGES: 2,730 lines, previously ~500)
   - **Old functionality preserved** in `merlin-driver-develop`
   - **New**: Incremental simulation support with causal event tracking
   - **New**: `diffAndSimulate()` - compares old/new plans and incrementally updates
   - **New**: `removePlanSpanById()` - removes directives and invalidates dependent computations
   - **New**: Combined history tracking (`getCombinedEventsByTask()`, `getCombinedCellReadHistory()`)
   - **New**: Stale task detection and re-execution
   - **New**: `oldEngine` field - maintains chain/tree of prior simulation states
   - **40+ TODO comments** - areas for future optimization/clarification
   - **Key Optimization**: Only re-runs tasks whose inputs changed

   **Testing Needed**:
   - ✅ Already tested via IncrementalSimTest, EdgeCaseTests, GeneratedTests
   - ⚠️ Performance testing against baseline
   - ⚠️ Memory usage monitoring (chain of engines grows)

2. **`SimulationDriver.java`** (303 lines added)
   - **New**: `diffAndSimulate()` method (main entry point for incremental sim)
   - **New**: `IncrementalSimAdapter` - adapts protocol to driver
   - **New**: `initSimulation()` and `getEngine()` accessors
   - **Changed**: Constructor now supports building from prior engine

   **Testing**: Covered by driver-level tests in merlin-driver-test

3. **`SimulationResults.java` / `CombinedSimulationResults.java`** (NEW, 321 lines)
   - **New**: `CombinedSimulationResults` - merges results from engine chain
   - **Purpose**: When querying results, walks back through `oldEngine` chain
   - **Complexity**: Must handle overlapping/overriding results from multiple engines

   **Testing**: Covered in integration tests

4. **`CheckpointSimulationDriver.java`** (13 lines changed)
   - Minor integration changes for new API

5. **Timeline Package** (`timeline/`)
   - **`TemporalEventSource.java`** (1,188 lines added, MAJOR)
     - Core of causal dependency tracking
     - Tracks which cells were read at which times
     - ⚠️ **Uncommitted change**: Added comment clarifying instance counter
   - **`EventGraph.java`** (NEW, 264 lines)
     - Represents causal relationships between events
   - **`Cell.java`** (45 lines changed)
     - Enhanced to track observation/modification history
   - **`LiveCells.java`** (89 lines changed)
     - Manages active cells with history tracking

6. **Engine Package** (`engine/`)
   - **`RangeMapMap.java`** (NEW, 204 lines) - Map of range maps for efficient queries
   - **`RangeSetMap.java`** (NEW, 132 lines) - Map of range sets for efficient queries
   - **`ProfileSegment.java`** (21 lines changed) - Enhanced with metadata
   - **`JobSchedule.java`** (18 lines added) - Scheduling utilities
   - **`Subscriptions.java`** (29 lines changed) - Cell subscription tracking

   **Testing**:
   - ✅ `RangeMapMapTest.java` (83 lines)
   - ✅ `RangeSetMapTest.java` (211 lines)

---

#### **scheduler-driver** (MEDIUM CHANGES)
**Purpose**: Goal-oriented scheduling algorithms

**Key File Changes**:

1. **`IncrementalSimulationFacade.java`** (NEW, 470 lines)
   - **Purpose**: Adapts incremental simulation for use by scheduler
   - **Key feature**: Caches simulation engines between scheduling iterations
   - **⚠️ CRITICAL ISSUE (Line 343-345)**: Currently disabled optimization
     ```java
     // TODO: turn back on to limit simulation span
     final var simulationDuration = this.planningHorizon.getAerieHorizonDuration(); // Always simulates entire plan!
     ```
   - **⚠️ ISSUE (Line 367-368)**: Resource info loss workaround
   - **Design**: Maintains single `driverEngineCache` (could be expanded to tree/DAG)

   **Testing Needed**:
   - ✅ Used in scheduler worker tests (SchedulingEdslIntegrationTests)
   - ⚠️ Need dedicated tests for facade caching logic
   - ⚠️ Need tests for optimization at line 343

2. **`SchedulerSimulationReuseStrategy.java`** (NEW, 26 lines)
   - Enum: `Checkpoint` vs `Incremental`
   - Controls which facade implementation is used

3. **`CheckpointSimulationFacade.java`** (5 lines changed)
   - Minor updates for consistency with new interface

4. **Test Changes**:
   - `SimulationUtility.java` (195 lines changed) - Test utilities
   - All scheduler tests updated to use `SchedulerSimulationReuseStrategy.Incremental`
   - `AnchorSchedulerTest.java` (118 lines changed)
   - `SimulationFacadeTest.java`, `TestApplyWhen.java`, etc.

---

#### **scheduler-worker** (SMALL CHANGES)
**Purpose**: Worker service that executes scheduling requests

**Key Changes**:

1. **`SynchronousSchedulerAgent.java`**
   - **Lines 383-388**: Switch statement to instantiate facade based on strategy
   - **Now uses**: `IncrementalSimulationFacade` by default
   - **Configuration**: `simReuseStrategy` passed from configuration

2. **`SchedulerWorkerAppDriver.java`**
   - **Line 156-157**: Reads `SCHEDULER_SIM_REUSE_STRATEGY` environment variable
   - **Default**: `SchedulerSimulationReuseStrategy.Incremental`

3. **`WorkerAppConfiguration.java`**
   - Added `simReuseStrategy` field to configuration record

**Testing**: Integration tests in scheduler-worker/src/test

---

#### **merlin-server** (SMALL CHANGES)
**Purpose**: Mission model management and simulation orchestration service

**Key Changes**:

1. **`SimulationReuseStrategy.java`** (NEW, 28 lines)
   - Enum: `CachedResults` vs `Incremental`
   - **Note**: Different from scheduler's strategy enum (different use case)

2. **`LocalMissionModelService.java`** (107 lines changed)
   - Integration of incremental simulation for direct API calls
   - **Default**: Still uses `CachedResults` (NOT incremental)

3. **`SimulationAgent.java`** (40 lines changed)
   - Plumbing for strategy configuration

4. **Test Changes**:
   - `StubMissionModelService.java` updated
   - `EventGraphFlattenerTest.java` updated

**Testing**: Unit tests in merlin-server/src/test

---

#### **merlin-worker** (SMALL CHANGES)
**Purpose**: Worker that executes simulation requests

**Key Changes**:
- `SimulationUtility.java` (9 lines) - Minor integration changes
- `SimulationResultsWriter.java` (13 lines) - Handles new result formats

---

#### **merlin-framework** (SMALL CHANGES)
**Purpose**: Framework for writing mission models

**Key Changes**:
- `ModelActions.java` (16 lines) - New actions for incremental sim support
- `Context.java` (6 lines) - Context enhancements
- `ThreadedTask.java` (14 lines) - Task handling updates
- Test: `ThreadedTaskTest.java` (18 lines added)

---

#### **merlin-framework-processor** (SMALL)
**Purpose**: Annotation processor for mission models

- `MissionModelGenerator.java` (12 lines) - Minor generation updates

---

#### **merlin-sdk** (TINY)
**Purpose**: Interface definitions for mission models

- `Scheduler.java` (3 lines) - Interface additions
- `Duration.java` (7 lines) - Utility methods
- **`SubInstantDuration.java`** (NEW, 172 lines) - Sub-instant timing for precise scheduling

---

#### **contrib** (SMALL)
**Purpose**: Convenience utilities for mission modelers

**Changes**:
- Cell classes updated for incremental sim compatibility:
  - `CounterCell.java` (9 lines)
  - `DurativeRealCell.java` (8 lines)
  - `LinearIntegrationCell.java` (27 lines)
  - `Accumulator.java` (7 lines)

---

### 📊 **Example & Test Changes**

#### **examples/banananation**
**Purpose**: Example mission model used for testing

**Key Changes**:
1. **`IncrementalSimTest.java`** (NEW, 613 lines)
   - High-level integration test using banananation model
   - Tests incremental simulation with realistic mission model

2. **`Timer.java`** (NEW, 475 lines)
   - Utility for performance measurement in tests

3. **`Configuration.java`, `Mission.java`** (small changes)
   - Model updates for incremental sim compatibility

4. **`ActivityInstanceTest.java`** (25 lines changed)
   - Test updates

**Testing Coverage**: ✅ Excellent - realistic mission model scenarios

---

#### **examples/foo-missionmodel**
- `FooSimulationDuplicationTest.java` (62 lines changed)
- Test updates for new APIs

---

### 🗄️ **Database & Infrastructure**

#### **deployment/** (LARGE CHANGES)
**Note**: Most changes are for **workspaces feature**, not incremental sim

**Incremental Sim Related**:
- `docker-compose.yml` - Environment variable for `SCHEDULER_SIM_REUSE_STRATEGY`
- Potentially other service configuration

**Workspaces Related** (separate feature):
- Database migrations (Aerie/25_workspaces_setup, 26_workspaces_cleanup)
- Hasura metadata changes
- Migration script `aerie_db_migration.py` (374 lines changed)
- Kubernetes configs for workspace-server

---

#### **e2e-tests/**
- `docker-compose-test.yml`, `docker-compose-many-workers.yml` - Config updates

---

#### **constraints** (SMALL)
**Purpose**: Constraint checking library

- `SimulationResults.java` (12 lines) - Updated for new results format
- `Violation.java` (6 lines) - Minor changes

---

#### **procedural** (SMALL)
**Purpose**: Post-simulation Kotlin DSL

- `MerlinToProcedureSimulationResultsAdapter.kt` (2 lines)
- Minor adapter updates

---

#### **stateless-aerie** (SMALL)
**Purpose**: CLI tool for stateless simulation

- `Main.java` (2 lines)
- Test updates for new result format

---

### 📦 **Build & Configuration**

#### **Gradle Changes**
- `settings.gradle` - Added new modules
- Various `build.gradle` files - Dependency updates
- `gradle.properties` - Version updates
- Gradle wrapper upgraded

#### **GitHub Actions**
- `.github/workflows/` - CI updates for new modules and tests

---

## Testing Summary

### ✅ **Existing Test Coverage**

| Test Suite | Lines | Coverage |
|------------|-------|----------|
| **IncrementalSimTest** (driver-test) | 611 | Core scenarios |
| **IncrementalSimTest** (banananation) | 613 | Integration with real model |
| **EdgeCaseTests** | 714 | Validates vs baseline |
| **GeneratedTests** | 525 | Property-based testing |
| **IncrementalSimPropertyTests** | 303 | Correctness properties |
| **RangeMapMapTest** | 83 | Data structure tests |
| **RangeSetMapTest** | 211 | Data structure tests |
| **Scheduler integration tests** | Various | Scheduler with incremental sim |

**Total**: ~3,000+ lines of new test code

### ⚠️ **Testing Gaps**

1. **Performance Benchmarks**
   - Need: Comparison of incremental vs full resimulation
   - Need: Memory usage tracking for engine chains
   - Need: Performance regression tests

2. **IncrementalSimulationFacade**
   - Need: Unit tests for caching logic
   - Need: Tests for optimization at line 343 (simulation span limiting)
   - Need: Tests for resource info handling (line 367)

3. **Stress Tests**
   - Need: Long engine chains (many incremental updates)
   - Need: Large plans (thousands of activities)
   - Need: Deep activity hierarchies

4. **Integration Tests**
   - ⚠️ Scheduler worker with incremental sim (exists but limited)
   - ⚠️ End-to-end with UI (if applicable)

5. **Negative Tests**
   - Need: What happens when memory is exhausted?
   - Need: Behavior with invalid/corrupted engine chains
   - Need: Concurrent access to engine chains

---

## Known Issues & TODOs

### 🔴 **Critical**

1. **IncrementalSimulationFacade Line 343-345**: Optimization disabled
   - Always simulates entire planning horizon
   - Defeats purpose of incremental simulation for partial queries
   - **Fix**: Re-enable `until` parameter

2. **IncrementalSimulationFacade Line 367-368**: Resource info loss
   - Workaround: Computing all results when should compute only activity timing
   - **Fix**: Investigate root cause of resource info loss in old engines

### ⚠️ **Medium Priority**

3. **SimulationEngine.java TODOs** (40+ comments)
   - Line 209: HACK for initial cells (DB/different mission model issue)
   - Line 420: Cache optimization for `earliestStaleTopics`
   - Line 1118: Stale read propagation in spawned children
   - Many others marked for efficiency/clarity improvements

4. **Memory Management**
   - No safeguards against unbounded engine chain growth
   - No mechanism to offload old engines to disk
   - Could cause OOM on long scheduling runs

5. **Code Duplication**
   - SonarQube: 24.9% duplication (threshold 3%)
   - Due to 3 parallel implementations (develop, retracing, main)
   - Consider: Remove develop/retracing after validation complete?

### ✅ **Low Priority / Future Enhancements**

6. **Engine Chain Optimization**
   - Currently: Linear chain, always from tip
   - Could: DAG/tree structure, reuse common ancestors
   - Trade-off: Complexity vs memory/performance

7. **Checkpoint Integration**
   - Combine incremental sim with persistent checkpoints
   - Would allow incremental sim without holding entire history in memory

8. **Simulation Config Changes**
   - Currently: Incremental sim invalidated by config changes
   - Could: Support config changes incrementally

9. **Store Incremental Results**
   - Currently: Full results reconstructed and stored
   - Could: Store only deltas

10. **UI Integration**
    - Controls for toggling incremental sim
    - Visualization of which parts were re-simulated
    - Memory usage indicators

---

## Files Requiring Immediate Attention

### 🔧 **Before Merge**

1. **Uncommitted Changes**:
   - ✅ `TemporalEventSource.java` - Just a clarifying comment, COMMIT IT
   - ⚠️ `IndexedSet.java`, `SimpleIndexedSet.java`, `SimpleIndexedSetTest.java` - MOVE TO SEPARATE BRANCH
   - ⚠️ Untracked: `docker-compose-test-auth.yaml`, `docker-compose.override.yml` - DECIDE: commit or .gitignore
   - ⚠️ Untracked: `output*.json`, `report-tests.sh` - DECIDE: commit or .gitignore

2. **Critical Bug Fixes**:
   - `IncrementalSimulationFacade.java` line 343 - Re-enable optimization
   - `IncrementalSimulationFacade.java` line 367 - Fix resource info loss

3. **Documentation**:
   - Update PR description (currently says facade not hooked in - FALSE!)
   - Document environment variables (`SCHEDULER_SIM_REUSE_STRATEGY`)
   - Add performance comparison data

### 📋 **For Review**

4. **High Complexity Files** (reviewers should focus here):
   - `merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/engine/SimulationEngine.java` (2,730 lines)
   - `merlin-driver/src/main/java/gov/nasa/jpl/aerie/merlin/driver/timeline/TemporalEventSource.java` (1,188 lines added)
   - `scheduler-driver/src/main/java/gov/nasa/jpl/aerie/scheduler/simulation/IncrementalSimulationFacade.java` (470 lines)

5. **Test Validation**:
   - Verify GeneratedTests catches regressions
   - Verify EdgeCaseTests validates against baseline
   - Run full test suite after merging develop

---

## Merge Checklist

- [ ] Merge latest `develop` (currently ~20 commits behind)
- [ ] Commit `TemporalEventSource.java` comment
- [ ] Move IndexedSet files to separate branch
- [ ] Clean up untracked files
- [ ] Fix IncrementalSimulationFacade critical TODOs (lines 343, 367)
- [ ] Run full test suite (`./gradlew test`)
- [ ] Run E2E tests (`./gradlew e2e-tests:e2eTest`)
- [ ] Update PR description with accurate status
- [ ] Address SonarQube duplication findings (or document why acceptable)
- [ ] Performance comparison: incremental vs baseline
- [ ] Memory usage analysis
- [ ] Update CLAUDE.md with incremental sim details

---

## Questions for Discussion

1. **Module Lifecycle**: Should `merlin-driver-develop` and `merlin-driver-retracing` remain long-term, or remove after validation?

2. **Default Behavior**: Should incremental sim be default for direct simulation API calls (not just scheduling)?

3. **Memory Limits**: What safeguards should we add for engine chain growth?

4. **Performance Requirements**: What's acceptable overhead compared to baseline?

5. **IndexedSet**: What was the original intended use case? (No comments in code)

---

## Useful Commands

```bash
# Compare incremental vs baseline performance
./gradlew merlin-driver-test:test --tests "*PerformanceTest*"

# Run all incremental sim tests
./gradlew merlin-driver-test:test

# Run scheduler integration tests
./gradlew scheduler-worker:test

# Check for TODOs
grep -r "TODO.*incremental" --include="*.java"

# Memory profiling (with JFR)
JAVA_OPTS="-XX:StartFlightRecording=filename=recording.jfr" ./gradlew test
```

---

## References

- **PR Discussion**: https://github.com/NASA-AMMOS/aerie/discussions/669
- **Presentations**:
  - [AI Group presentation (Slack)](https://jpl.slack.com/archives/C04NJK8E52T/p1712010300532409)
  - [IWPSS 2025 slides](https://docs.google.com/presentation/d/1VrxTQtf2vEuQY1DtZs80T9zLqwOxaVyL/edit)
  - [IWPSS 2025 paper](https://drive.google.com/file/d/1KMcD9XbDMPS63_sT0IfGGmuQ-npzxmVT/view)

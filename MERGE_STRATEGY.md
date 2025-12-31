# Merge Strategy for Incremental Simulation Branch

## Overview
Merging `develop` into `prototype/incremental-sim` branch to bring it up to date before final PR review.

**Current Status**:
- Branch is ~20 commits behind develop
- Last merge: 805f8ce4c (Aug 8, 2025) - merged v3.6.0 release
- Behind by: JUnit upgrade (5.10.0 → 6.0.1), bug fixes, action-server updates

---

## GitHub CI Requirements

Based on `.github/workflows/test.yml`, PRs must pass:

### 1. **Unit Tests** (Required)
```bash
./gradlew assemble --parallel
./gradlew test --parallel
```
- Runs all module unit tests
- Required to pass for merge

### 2. **E2E Tests** (Required)
```bash
./gradlew e2e-tests:buildAllProcedureJars
docker compose -f ./e2e-tests/docker-compose-test.yml up -d --build
./gradlew e2eTest
```
- Starts full Docker stack (without auth)
- Runs integration tests in `e2e-tests` and `db-tests` modules
- Sequencing server tests included
- Required to pass for merge

### Other Workflows
- `security-scan.yml` - Security scanning
- `pgcmp.yml` - Database comparison tests
- `publish.yml` - Publishing artifacts (on merge)

---

## Historical Merge Conflict Patterns

### Most Recent Merge: 805f8ce4c (Aug 8, 2025)

**Files with Conflicts**:

1. **`docker-compose.yml`**
   - **This branch added**: `SPICE_KERNEL_PATH` volume mount to sequencing-server
   - **Develop added**: `aerie_workspace` service (workspaces feature)
   - **Resolution**: Keep both changes

2. **`settings.gradle`**
   - **This branch added**: Incremental sim test modules (merlin-driver-protocol, etc.)
   - **Develop added**: `workspace-server` module
   - **Resolution**: Keep both, maintain alphabetical ordering in sections

### Second Most Recent: ed8963d6b (Aug 2, 2025)

Similar pattern - mostly workspaces feature additions that don't conflict with incremental sim work.

---

## Expected Conflicts in Current Merge

Based on diff between HEAD and origin/develop:

### 1. **JUnit Upgrade Conflicts** (MEDIUM RISK)
- Develop upgraded JUnit 5.10.0 → 6.0.1
- May affect test syntax/imports in incremental sim tests
- **Files to watch**: `merlin-driver-test/**/*Test.java`, `examples/banananation/src/test/**`

### 2. **Build Configuration** (LOW RISK)
- `build.gradle` files may have dependency version conflicts
- **Strategy**: Accept develop's versions

### 3. **Action Server Changes** (LOW RISK)
- This branch hasn't modified action-server
- **Strategy**: Accept all develop changes

### 4. **Sequencing Server** (LOW RISK)
- Package.json/package-lock.json updates
- **Strategy**: Accept develop's versions

---

## Pre-Merge Testing Strategy

### Step 1: Baseline Tests (BEFORE merge)
Run on current `prototype/incremental-sim` branch to establish baseline:

```bash
# Unit tests
./gradlew clean assemble --parallel
./gradlew test --parallel --continue

# Check for failures
find ./ -name "index.html" -exec egrep -Hsni -A1 'failures' '{}' \; | grep counter
```

**Expected Result**: All tests should PASS (branch is currently working)

### Step 2: Run E2E Tests (BEFORE merge)
```bash
# Build procedure JARs
./gradlew e2e-tests:buildAllProcedureJars

# Start services
docker compose -f ./e2e-tests/docker-compose-test.yml up -d --build

# Wait for startup
sleep 30

# Run tests
./gradlew e2eTest

# Check logs if needed
docker compose -f ./e2e-tests/docker-compose-test.yml logs -t

# Cleanup
docker compose -f ./e2e-tests/docker-compose-test.yml down
docker volume prune --force
```

**Expected Result**: All E2E tests should PASS

---

## Merge Execution Plan

### Step 1: Create Merge Branch
```bash
git checkout prototype/incremental-sim
git checkout -b merge/develop-into-incremental-sim-2025-12-30
```

### Step 2: Merge develop
```bash
git fetch origin develop
git merge origin/develop
```

### Step 3: Resolve Conflicts

#### docker-compose.yml
- Keep SPICE_KERNEL_PATH from our branch
- Accept workspace service from develop
- Merge sections carefully

#### settings.gradle
- Keep incremental sim modules
- Add workspace-server module
- Maintain proper ordering

#### JUnit-related files
- Update test imports if needed: `org.junit.jupiter.*`
- Check for deprecated APIs
- May need to update assertion syntax

### Step 4: Build After Merge
```bash
./gradlew clean
./gradlew assemble --parallel
```
**Must succeed before proceeding**

### Step 5: Run Tests After Merge
```bash
# Unit tests
./gradlew test --parallel --continue

# E2E tests
./gradlew e2e-tests:buildAllProcedureJars
docker compose -f ./e2e-tests/docker-compose-test.yml up -d --build
sleep 30
./gradlew e2eTest
docker compose -f ./e2e-tests/docker-compose-test.yml down
```

**Must pass before merging to main branch**

### Step 6: Merge to Main Branch
```bash
git checkout prototype/incremental-sim
git merge --no-ff merge/develop-into-incremental-sim-2025-12-30
git push origin prototype/incremental-sim
```

---

## Post-Merge Verification

### 1. GitHub Actions
- Push to branch triggers CI
- Verify unit tests pass
- Verify e2e tests pass
- Check SonarQube results

### 2. Incremental Sim Specific Tests
Run incremental sim test suite specifically:
```bash
./gradlew merlin-driver-test:test
./gradlew examples:banananation:test --tests "*IncrementalSim*"
```

### 3. Scheduler Integration
Verify scheduler still uses incremental sim:
```bash
./gradlew scheduler-worker:test
```

---

## Rollback Plan

If merge causes unfixable issues:

```bash
git checkout prototype/incremental-sim
git reset --hard origin/prototype/incremental-sim
git branch -D merge/develop-into-incremental-sim-2025-12-30
```

---

## Known Issues to Monitor

1. **JUnit API Changes**
   - Jupiter 6.0.1 may have breaking changes
   - Watch for: `@Test` annotation changes, assertion API changes
   - Reference: https://junit.org/junit5/docs/current/release-notes/

2. **Gradle Compatibility**
   - Wrapper was upgraded in branch
   - Should be compatible but verify

3. **Docker Compose Version**
   - Both branches use v3.7
   - No issues expected

---

## Success Criteria

Merge is successful when:
- [ ] No merge conflicts remain
- [ ] `./gradlew assemble --parallel` succeeds
- [ ] `./gradlew test --parallel` passes (all modules)
- [ ] `./gradlew e2eTest` passes
- [ ] Incremental sim tests specifically pass
- [ ] No new SonarQube critical issues
- [ ] GitHub Actions workflows pass

---

## Timeline Estimate

- Baseline testing: 15-30 min (parallel)
- Merge and conflict resolution: 15-30 min
- Post-merge testing: 20-40 min (parallel)
- **Total**: ~1-1.5 hours

---

## Notes from Past Merges

1. **Workspace feature** is the main addition in develop - largely independent
2. **SPICE_KERNEL_PATH** in our branch is for mission model support - keep it
3. **Incremental sim modules** are unique to this branch - never conflict with develop
4. **Test module structure** hasn't changed - low risk
5. **JUnit upgrade** is the main technical risk - may need test updates

---

## Contact/Resources

- **JUnit 6.0.1 Release Notes**: https://junit.org/junit5/docs/6.0.1/release-notes/
- **Previous merge commits**: 805f8ce4c, ed8963d6b
- **PR Discussion**: https://github.com/NASA-AMMOS/aerie/discussions/669

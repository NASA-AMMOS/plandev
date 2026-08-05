# Changelog

## 0.1.1 (2026-07-29)

### Added
- **Cell evolution.** `registrar.cell(initial, evolution=fn)` now wires the user-defined
  evolution function through to Java's `CellType.step()`, so cell values evolve
  automatically as simulation time advances — no daemon activity needed. Includes support
  for clamped linear cells with configurable bounds.
- **Evolving-cell resource projections.** Evolving cells produce correct resource profiles
  (discrete snapshots of the stepped value) in the PlanDev UI.
- **`MissionModelBase` helper class.** Mission model classes can now inherit from
  `MissionModelBase` for improved type checking and IDE support, alongside the existing
  `@MissionModel` decorator.
- **CLI `--version` flag.** `pymerlin --version` prints the installed version.
- **`build-shim.sh` script.** One-command rebuild of the shim JAR
  (`./scripts/build-shim.sh`).

### Changed
- `pymerlin-shim` now compiles against published PlanDev artifacts from GitHub Packages
  instead of requiring a local `plandev/` checkout.

### Fixed
- Linear resource clamp fix: prevent extrapolation past bounds and ensure correct segment
  scheduling.

---

## 0.1.0 (2026-07-23)

### Added
- **In-process GraalPy execution (Phases 1–4).** A packaged model now runs in-process
  inside the PlanDev worker's JVM via an embedded GraalPy interpreter — no subprocess, no
  stdin/stdout JSON protocol. Java and Python call each other directly across the polyglot
  boundary.
- **`call()` support.** `call(child(...))` genuinely blocks the parent activity until the
  child completes.
- **Real `wait_until`.** `wait_until(predicate)` passes the Python predicate to Java as a
  `BooleanSupplier` wrapped in a PlanDev `Condition` — dependency-tracked blocking, not
  polling.
- **Linear (interpolated) resources.** `registrar.linear(initial, rate)` declares a
  continuously-integrating resource backed by Aerie's `RealDynamics`, with
  `.set_rate(r)` for discrete rate changes.
- **Model configuration.** A model's `__init__` parameters (after `self` and `registrar`)
  are automatically exposed as simulation configuration in the PlanDev UI.
- **Worker-image contract documentation.** The README now documents the
  `python-resources/` layout, the fixed venv package set (`pymerlin` + `numpy` +
  `spiceypy`), and how to add a missing dependency (image rebuild, not JAR change).
- **Decoupled builds.** The worker/server image build no longer requires a local pymerlin
  checkout — `install.sh` pulls pymerlin from a pinned git tag. `pymerlin-shim` no longer
  requires a local `plandev/` checkout to compile.

### Changed
- Architecture docs (`architecture.md`, `shim-protocol.md`) rewritten to describe the
  in-process GraalPy design instead of the superseded py4j / subprocess protocols.
- All "Aerie" references renamed to "PlanDev" across docs.

### Removed
- Subprocess bridge (`Protocol.java`, `PythonProcess.java`, `SubprocessBridge.java`).
- Python-side subprocess machinery (`_ActivityRunner`, `_send`/`_recv`, `_run_server`,
  `pymerlin/_server/` CLI package).
- CPython subprocess blocks from both Dockerfiles.

---

## 0.0.9 and earlier

See the git history for changes prior to the GraalPy migration.

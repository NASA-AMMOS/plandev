# pymerlin internal

Implementation details behind pymerlin's public API. Two execution paths live here, both
driving the same `@MissionModel` (or `MissionModelBase` subclass):

- `_framework.py` — the pure-Python `simulate()` engine used for local prototyping (no Java,
  no subprocess). Supports cell evolution (`evolution=fn`) as of 0.1.1.
- `_server.py` — the entry points the Java shim calls when a packaged model runs in-process on
  GraalPy inside a PlanDev worker (host calls, no subprocess, no protocol; see `roadmap.md`
  §6/§7). Cell evolution functions are passed to Java via `getEvolutionFunctions()` and called
  from `CellType.step()`.

Ideally none of these details leak out to user-facing code.

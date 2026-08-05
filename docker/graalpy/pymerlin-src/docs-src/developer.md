# Developing pymerlin

For pymerlin development, you'll need:
- python 3.10 or higher — for the framework, tests, and local `simulate()` (pure Python).
- Java 21 JDK — **only** to rebuild the shim JAR from source (below). Not needed to run the
  Python tests or local simulations.

Additional libraries:
- build (for packaging)
- twine (for publishing)
- pytest (for running tests)

## Building the shim JAR

The shim JAR (`pymerlin-shim.jar`) is what `pymerlin package` copies into an uploadable
mission-model JAR; it implements PlanDev's `MerlinPlugin` SPI and runs the model in-process via
GraalPy (see [architecture](./architecture.md) and [shim-protocol](./shim-protocol.md)).
The built JAR is committed to `pymerlin/_internal/jars/`, so installing pymerlin needs no
JDK or Gradle. Rebuild and reinstall it after any change under `java/pymerlin-shim/src`:

```shell
./scripts/build-shim.sh
```

This runs `./gradlew assemble` in `java/` and copies the resulting JAR to
`pymerlin/_internal/jars/`. Commit the rebuilt JAR alongside the Java change. Python-only
changes don't affect the shim and need no rebuild.

`pymerlin package` copies whatever JAR is at that path, so forgetting to rebuild after a
Java change means packaged models ship stale shim classes.

## Testing

All tests are located in the `tests` directory, and are defined using pytest.

As of writing, tests can only be run in your current environment - so first run `pip install .`, and then `pytest`.

Key test files:
- `tests/test_simulation.py` — core simulation tests (delays, emits, spawn, call, wait_until).
- `tests/test_cell_evolution.py` — cell evolution tests (evolving cells, clamped linear
  bounds, evolution projections). Added in 0.1.1.

Future aspiration: use `tox` to test on multiple versions of python.

Future aspiration: automated testing of tutorial snippets in docs

## Performance analysis
While the [architecture](./architecture.md) document asserts that performance is secondary to intuitiveness, it cannot
be completely ignored. This section should be filled out with procedures and practices for measuring pymerlin performance.

Some starting points for future exploration:
- `cProfile` is the built-in python profiler. It can be useful for understanding the call graph, and getting a sense for
  where time is spent, but it must be noted that it adds non-negligible overhead to the runtime of the program
- [scalene](https://github.com/plasma-umass/scalene?tab=readme-ov-file) promises a lot of information at low overhead,
  and includes memory profiling as well (which may well be a critical metric for pymerlin given all the caching going on)
- Local `simulate()` is now a single pure-Python process, so there is no interprocess
  communication to profile (the old py4j/subprocess "chattiness" concern is gone). For the
  packaged path, the model runs in-process on GraalPy inside the PlanDev worker JVM; profiling
  there means measuring one JVM (GraalPy's JIT may use multiple cores, and native packages
  allocate outside the JVM heap — see `roadmap.md` §11.4), not a cross-process boundary.

We would also want a standard benchmark to run for these measurements.
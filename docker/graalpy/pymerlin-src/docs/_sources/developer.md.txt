# Developing pymerlin

For pymerlin development, you'll need:
- Java 21 JDK
- python 3.9 or higher

Additional libraries:
- build (for packaging)
- twine (for publishing)
- pytest (for running tests)

## Building pymerlin.jar

```shell
cd java
./gradlew assemble
mv pymerlin/build/libs/pymerlin.jar ../pymerlin/_internal/jars
```

## Testing

All tests are located in the `tests` directory, and are defined using pytest.

As of writing, tests can only be run in your current environment - so first run `pip install .`, and then `pytest`.

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
- TODO we need a way to evaluate the "chattiness" of the interprocess communication
- TODO consider whether we need to also measure the Java process - perhaps simply a measure of total memory footprint
  would be sufficient?

We would also want a standard benchmark to run for these measurements.
# Timelines

This library provides tools for querying and manipulating "timelines" from an PlanDev plan or set of
simulation results. This includes things like resource profiles, activity instances, and activity directives,
but can be extended to support more kinds if needed.

See [MODULE_DOCS.md](./MODULE_DOCS.md) for a description of the architecture and design of the library.

- Building and testing: `./gradlew :procedural:timeline:build`
- Generating a jar for local experimentation: `./gradlew :procedural:timeline:shadowJar`
  - jar will be available at `procedural/timeline/build/libs/timeline-all.jar`

See `/procedural/README.md` for instructions on generating viewing documentation.

## Potential future optimizations

- **caching/memoization:** currently collecting a timeline twice will result in it being
  computed twice. Automatically memoizing the results has potential for unsoundness,
  but to be fair, so does the entire concept of evaluating on restricted bounds. But if
  we do it right, it could give theoretically unbounded speedup for complex timelines.
- **inlining durations:** Turn the Duration class into an `inline value` class. This speeds
  up the entire library by about 20%, based on a rudimentary benchmark. On the downside,
  it makes the interface in Java use a bunch of longs instead of duration objects. Should
  be possible to do cleanly, but will take some cleverness.
- **streaming:** rework the core to be create streams instead of lists. It should be somewhat faster,
  but I don't know how much. The main utility is that with segment streaming from simulation,
  you could evaluate timelines in parallel with simulation.

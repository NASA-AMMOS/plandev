# Discrete Event Simulation

Requirements:
- Allow a mission model to express time-dependent state in a way that can be tracked and managed by the host system
- Evaluate conditions whenever their dependencies change
- Evaluate resources whenever their dependencies change
- Allow for tasks to operate transactionally

Quality attributes:
- Avoid wasted work - avoid computing the current state of a cell unless it is queried
- Minimize needless allocations - Cells should be mutable and not require duplication on every step

## The Theory

The merlin concept of a "Cell" implements a more generic simulation concept called a "State Variable". Traditionally, every state variable in a simulation is given an initial value, and it keeps that value until an *event* is emitted that changes that value. Put another way, the value of a State Variable at time `t` can be computed by some function `f : Value x History -> Value`, where the first `Value` is the initial value, and `History` is the list of events that have occurred before time `t`.

This function `f` should have the following property: for all partitions of `History` into a `prefix` and a `suffix`, `f(f(initial, prefix), suffix) = f(initial, prefix + suffix).` To paraphrase, as time moves forward, we should be able to "forget" both the initial value and the prefix once we've applied `f` to it.

There are two additional enhancements we add to this formulation:
- continuous evolution, borrowed from [Hybrid systems](https://en.wikipedia.org/wiki/Hybrid_system)
- concurrent events (TODO)

Continuous evolution means that the value of a State Variable can change even in the absence of any events. This requires some separation between the *dynamics* of a State Variable and its *value*. The dynamics describes the future evolution of the State Variable, while its value describes its current state.

Concurrent events exist to model two phenomena:
- Two or more independent *tasks* are scheduled to act at the same time, and we'd like to avoid arbitrarily serializing their actions
- A single task *spawns* another to run in parallel, and again we'd like to avoid arbitrary choices regarding in what order the parent and child perform their actions.

In order to enable tasks to have *[read-your-writes](https://en.wikipedia.org/wiki/Consistency_model#Read-your-writes_consistency)* semantics, we introduce the notion of a task *step* with certain transactional guarantees.

Further reading:
- https://nasa-ammos.github.io/aerie-docs/mission-modeling/advanced-the-merlin-interface/
- https://en.wikipedia.org/wiki/Hybrid_system
- https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type
- https://komputasi.wordpress.com/wp-content/uploads/2018/03/mvsteen-distributed-systems-3rd-preliminary-version-3-01pre-2017-170215.pdf
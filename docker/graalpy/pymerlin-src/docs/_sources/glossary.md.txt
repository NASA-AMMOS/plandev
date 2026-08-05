# Glossary

% Entries should be sorted alphabetically, cross-reference other entries, and end in periods.)

:::{glossary}
Aerie
  A suite of planning and scheduling, modeling and simulation, constraint checking and sequencing tools.

Activity Type
  A named action or behavior of the system.

Cell
  A container for a piece of simulation state. To guarantee correctness of simulation results, all mutable state must be
  tracked in cells.

Daemon Task
  A Task that is spawned during mission model initialization, meaning it starts execution at the beginning of simulation
  rather than as a result of a Directive.

Directive
  A request to instantiate a certain Activity Type with certain arguments at a certain time.

Effect
  An action that changes the value of a cell. Effects are defined as functions from an old value to a new value.

Effect Model
  The body of the function describing an activity's behavior during simulation.

Merlin
  The modeling and simulation component of Aerie.

Mission model
  A description of a system that Aerie understands. This primarily includes definitions of Activity Types and Resources.

Profile
  A piece-wise defined function from time to a value

Profile Segment
  A single piece of the Profile with a start and end time. Profile segments within one profile must be contiguous and
  non-overlapping.

Resource
  A 

Span
  A component of simulation results representing a start time, a duration, an optional parent, and some metadata.

Validation
  A predicate on the arguments to an activity. Violation of a predicate does not preclude execution of the activity, but
  rather serves as a warning.
:::

## Other terminology:
There are some general terms that Aerie uses very specifically, that may not be worth a glossary entry, but do deserve
some attention.

### Parameters vs Arguments
Take as an example the `add1` function below:
```python
def add1(x):
    return x + 1
```
and this invocation of the add function:
```python
add1(5)
```
In this example, `x` is a parameter of the `add1` function, while `5` is an argument to the invocation of the `add1` 
function.

### Register vs Registrar
A `Register` is an analogy to a [processor register](https://en.wikipedia.org/wiki/Processor_register), which can store
a single value at a time, and supports the `get` operation to read that value, and the `set` operation to overwrite the
entire value.

A `Registrar` means a "record-keeper" - you can "register" (verb) something with a registrar. In `pymerlin`, the
registrar is an object provided to the mission model at initialization time, which the model can use to register
its cells and resources.

% You can use {term}`MyST` to create glossaries.)
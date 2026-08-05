# Architecture

pymerlin is to merlin as [pyspark](https://spark.apache.org/docs/latest/api/python/index.html) is to [spark](https://spark.apache.org/).
This means that pymerlin uses [py4j](https://www.py4j.org/) as a bridge
between a python process and a java process. This allows pymerlin to use the Aerie simulation engine directly, without
having to re-implement it in python.

This means that running `simulate` starts a subprocess using `java -jar /path/to/pymerlin.jar`.

## Approachability over performance

The main tenet of pymerlin is approachability, and its aim is to enable rapid prototyping of models and activities.
While where possible, performance will be considered, it is expected that someone who wants to seriously engineer the
performance of their simulation will port their code to Java - which has the double benefit of removing socket
communication overhead, as well as giving the engineer a single Java process to instrument and analyze, rather than a
hybrid system, which may be more difficult to characterize.

## Round trips
Some objects that the mission model provides to the simulation driver are _pass-through_ objects - i.e. the driver
merely returns these objects to the mission model when it is appropriate to do so. These objects need not be converted
to Java types - it is sufficient to pass a handle to the simulation driver, as long as the handle can be used to look
up the original object when needed. This is why certain global variables exist in `_globals.py` - they are used to cache
python objects and pass only their id to the Java process.

:::{warning}
As implemented, this can be a source of a memory leak. Some careful cleanup is required to make this approach viable
for larger use cases 
:::

For resources and activity arguments (i.e. things that are represented as "SerializedValue" on the Java side), it is
important _not_ to make use of the python cache. This will help with integrating a python mission model with the rest
of the Aerie system by allowing the python code to handle inputs generated elsewhere in the system, and provide outputs
that can be understood by the rest of the system.

## Async/await vs threads

pymerlin originally required tasks to be defined as async functions (a.k.a coroutines), but that was in tension with
the "approachability over performance" principle. Version 0.0.8 replaced async functions with regular functions, and
used threads instead. This significantly simplified the implementation, as well as the mental model. If thread switching
turns out to be a performance bottleneck, async tasks should be reintroduced as an optional alternative. 

## Use pythonic idioms

To the extent possible, pymerlin should expose pythonic APIs. This means:
- Use `snake_case` for functions, methods, and variables
- Use `CAPITAL_SNAKE_CASE` for constants
- Use `lowercasenospaces` for modules
- Use `TitleCase` for classes
- Leverage context managers for cleanup
- Prefer duck typing to explicit inheritance
- Public API should include docstrings and type annotations
- Prefix private attributes with one underscore
- Prefer simple attributes to properties
- Override operators where appropriate
- Enumerate entry points into public API in the __init__.py's __all__ attribute.

Consult [PEP8](https://peps.python.org/pep-0008/#naming-conventions) for additional ideas.

### But what about Aerie idioms?
For now, stick to pythonic style - cross that bridge when uploading pymerlin can build Aerie-compatible jars

## Emphasize debuggability
On one hand, we need to do our best not to show users Java stack traces - or at least show them when they're useful and
not overwhelming. On the other hand, we must not _obscure_ useful debugging information. This is a delicate balance to
strike.
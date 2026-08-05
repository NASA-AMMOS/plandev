# Common Errors

This section describes some errors you might encounter, and what to do about them

## ImportError - circular import

```
ImportError: cannot import name '...' from partially initialized module 'mission' (most likely due to a circular import)
```

This error means you have two python files that import each other. This can occur if you have an activity defined in
one file, and a model in another, and the two reference each other.

The recommended practice here is _not_ to import activities from the model file - activities should reference the model,
not the other way around. The activity files will need to be imported from your main file prior to running simulation.
Convenient as it may seem, we do _not_ recommend using your model file as your main file - make a separate `main.py` and 
import both the model and the activities.

## Stack traces

TODO: Borrow some advice from the pyspark docs https://spark.apache.org/docs/latest/api/python/development/debugging.html#stack-traces

# Enumerated and Derived Resources

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

In addition to our on-board camera, let's imagine that we also have an instrument on-board that is continuously
collecting data, say a magnetometer, based on a data collection mode. Perhaps at especially interesting times in the
mission, the magnetometer is placed in a high rate collection mode and at other times remains in a low rate collection
mode. For our model, we want to be able to track the collection mode over time along with the associated data collection
rate of that mode.

The first thing we'll do to accomplish this is create a python dictionary called `MagDataCollectionMode` that gives us
the list of available collection modes along with a mapping of those modes to data collection rates. Let's say that we
have three modes, `OFF`, `LOW_RATE`, and `HIGH_RATE` with values `0.0`, `500.0`, and `5000.0`, respectively. After
coding this up, our enum should look like this:

:::{testcode}
MagDataCollectionMode = {
    "OFF": 0.0, # kbps
    "LOW_RATE": 500.0, # kbps
    "HIGH_RATE": 5000.0 # kbps
}
:::

% TODO: consider using aenum to allow for duplicate right-hand-sides of these
assignments https://stackoverflow.com/a/35968057/15403349

With our enumeration built, we can now add a couple of new resources to our `DataModel` class. The first resource, which
we'll call `mag_data_mode`, will track the current data collection mode for the magnetometer. Declare this resource as a
discrete `cell` of type `MagDataCollectionMode` and then add the following lines of code to the constructor
to initialize the resource to `OFF` and register it with the UI.

```python
self.mag_data_mode = registrar.cell("OFF")
registrar.resource("mag_data_mode", mag_data_mode.get)
```

As you can see, declaring and defining this resource was not much different than when we built `recording_rate` except
that the type of the resource is an Enum rather than a number.

Another resource we can add is one to track the numerical value of the data collection rate of the magnetometer, which
is based on the collection mode. In other words, we can derive the value of the rate from the mode. Since we are
deriving this value and don't intend to emit effects directly onto this resource, we can declare it as a
discrete `Resource` of type `Double` instead of a `cell`.

When we go to define this resource in the constructor, we need to tell the resource to get its value by mapping
the `mag_data_mode` to its corresponding rate. A special static method in the `DiscreteResourceMonad` class
called `map()`
allows us to define a function that operates on the value of a resource to get a derived resource value. In this case,
that function is simply the getter function we added to the `MagDataCollectionMode`. The resulting definition and
registration code for `mag_data_rate` then becomes

```python
self.mag_data_rate = self.mag_data_mode.map(lambda mode: MagDataCollectionMode[mode])
registrar.resource("mag_data_rate", self.mag_data_rate.get)
```

:::{info}
Instead of deriving a cell value from a function using `map()`, you can directly add or subtract cells, for example:
`````python
self.total_data_rate = self.mag_data_rate + self.recording_rate
````
:::

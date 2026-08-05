# Getting Started

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

Welcome Aerie modeling padawans! For your training today, you will be learning the basics of mission modeling in Aerie
by building your own simple model of an on-board spacecraft solid state recorder (SSR). This model will track the
recording rate into the recorder from a couple instruments along with the integrated data volume over time. Through the
process of building this model, you'll learn about the fundamental objects of a model, activities and resources, and
their structure. You'll be introduced to the different categories of resources and learn how you define and implement
each along with restrictions on when you can/can't modify them. As a bonus, we will also cover how you can make your
resources "unit aware" to prevent those pesky issues that come along with performing unit conversions and how you can
test your model without having to pull your model into an Aerie deployment.

Let the training begin!

## Installing pymerlin

If you haven't already, go to the [quickstart](../../quickstart.md) guide to get set up with pymerlin.

## Creating a Mission Model

Start by creating a `mission.py` file with the following contents:

```python
from pymerlin import MissionModel

@MissionModel
class Model:
    def __init__(self, registrar):
        self.data_model = DataModel(registrar)

class DataModel:
    def __init__(self, registrar):
        "YOUR CODE HERE"
```

## Your First Resource

We will begin building our SSR model by creating a single resource, `recording_rate`, to track the rate at which data is
being written to the SSR over time. As a reminder, a **Resource** is any measurable quantity whose behavior we want to
track over the course of a plan. Then, we will create a simple activity, `collect_data`, that updates the `recording_rate`
by a user-specified rate for a user-specified duration. This activity is intended to represent an on-board camera taking
images and writing data to the spacecraft SSR.

Although we could define the `recording_rate` resource directly in the pre-provided top-level `Mission` class, we'd like
to keep that class as simple as possible and delegate most of model's behavior definition to other, more focused
classes. With this in mind, let's create a new class within the `missionmodel` package called `DataModel`, which we will
eventually instantiate within the `Mission` class.

In the `DataModel` class, declare the `recording_rate` resource by replacing `"YOUR CODE HERE"` with the following line
of code:

```python
self.recording_rate = registrar.cell(0)  # Megabits/s
```

<!--:::{tip}
As you are coding, take advantage of your IDE to auto import the modeling framework classes you need like `cell`.
:::-->

Let's tease apart this line of code and use it as an opportunity to provide a brief overview of the various types of
resources available to you as a modeler. The mission modeling framework provides two primary classes from which to
define resources:

1. `cell` - resource whose value can be explicitly updated by activities or other modeling code after it has
   been defined. Updates to the resource take the form of "Effects" such as `increase`, `decrease`, or `set`. The values
   of this category of resource are explicitly tracked in objects called "Cells" within Aerie, which you can read about
   in detail in
   the [Aerie Software Design Document](https://ammos.nasa.gov/aerie-docs/overview/software-design-document/#cells) if
   you are interested.
2. `Resource` - resource whose value cannot be explicitly updated after it has been defined. In other words, these
   resources cannot be updated via "Effects". The most common use of these resources are to create "derived" resources
   that are fully defined by the values of other resources (we will have some examples of these later). Since these
   resources get their value from other resources, they actually don't need to store their own value within a "Cell".
   Interestingly, the `cell` class extends the `Resource` class and includes additional logic to ensure
   values are correctly stored in these "Cells".

From these classes, there are a few different types of resources provided, which are primarily distinguished by how the
value of the resource progresses between computed points:

- `Discrete` - resource that maintains a constant value between computed points (i.e. a step function or piecewise
  constant function). Discrete resources can be defined as many different types such as `Boolean`, `Integer`, `Double`,
  or an enumeration. These types of resources are what you traditionally find in discrete event simulators and are the
  easiest to define and "effect".
- `Linear` - resource that has a linear profile between computed points. When computing the value of such resources you
  have to specify both the value of the resource at a given time along with a rate so that the resource knows how it
  should change until the next point is computed. The resource does not have to be strictly continuous. In other words,
  the linear segments that are computed for the resource do not have to match up. Unlike discrete resources, a linear
  resource is implicitly defined as a `Double`.
- `Polynomial` - generalized version of the linear resource that allows you to define resources that evolve over time
  based on polynomial functions.
- `Clock` - special resource type to provide "stopwatch" like functionality that allows you to track the time since an
  event occurred.

TODO: Add more content on `Clock`

:::{note}
Polynomial resources currently cannot be rendered in the Aerie UI and must be transformed to a linear resource (an
example of this is shown later in the tutorial)
:::

Looking back at our resource declaration, you can see that `recording_rate` is a `cell` (we will emit effects
on this resource in our first activity) of the type `Discrete<Double>`, so the value of the resource will stay constant
until the next time we compute effects on it.

Next, we must define and initialize our `recording_rate` resource, which we can do in a class constructor that takes one
parameter we'll called `registrar` of type `Registrar`. You can think of the `Registrar` class as your link to what will
ultimately get exposed in the UI and in a second we will use this class to register `recording_rate`. But first, let's
add the following line to the constructor we just made to fully define our resource.

Both the `cell` and `Discrete` classes have static helper functions for initializing resources of their type.
If you included those functions via `import static` statements, you get the simple line above. The `discrete()` function
expects an initial value for the resource, which we have specified as `0.0`.

The last thing to do is to register `recording_rate` to the UI so we can view the resource as a timeline along with our
activity plan. This is accomplished with the following line of code:

```python
registrar.resource("recording_rate", self.recording_rate.get);
```

:::{note}
Notice that `self.recording_rate.get` does not have parenthesies at the end. This is because we are registering
the `get`
function itself as a resource. Resources are functions that perform computations on cells
:::

The first argument to this `resource` function is the string name of the resource you want to appear in the simulation
results,
and the second argument is the resource itself.

You have now declared, defined, and registered your first resource and your `DataModel` class should look something like
this:

```python
class DataModel:
    def __init__(self, registrar):
        self.recording_rate = registrar.cell(0)
        registrar.resource("recording_rate", self.recording_rate.get)
```

With our `DataModel` class built, we can now instantiate it within the top-level `Model` class as a member variable of
that class. The `Registrar` that we are passing to `DataModel` is unique in that it can log simulation errors as a
resource, so we also need to instantiate one of these special error registrars as well. After these additions,
the `Mission` class should look like this:

```python
from pymerlin import MissionModel

@MissionModel
class Model:
    def __init__(self, registrar):
        self.data_model = DataModel(registrar)

class DataModel:
    def __init__(self, registrar):
        self.recording_rate = registrar.cell(0)
        registrar.resource("recording_rate", self.recording_rate.get)
```

## Your First Activity

Now that we have a resource, let's build an activity called `collect_data` that emits effects on that resource. We can
imagine this activity representing a camera on-board a spacecraft that collects data over a short period of time.
Activities in Aerie follow the general definition given in
the [CCSDS Mission Planning and Scheduling Green Book](https://public.ccsds.org/Pubs/529x0g1.pdf)

> "An activity is a meaningful unit of what can be planned… The granularity of a Planning Activity depends on the use
> case; It can be hierarchical"

Essentially, activities are the building blocks for generating your plan. Activities in Aerie follow a class/object
relationship
where [activity types](https://nasa-ammos.github.io/aerie-docs/mission-modeling/activity-types/introduction/) - defined
as a class in Java - describe the structure, properties, and behavior of an object and activity instances are the actual
objects that exist within a plan.

Since activity types are implemented by async functions in python, create a new function called `collect_data` and add the
following decorator above that function, which allows pymerlin to recognize this function as an activity type.

```python
@Model.ActivityType
def collect_data(model):
    pass
```

:::{note}
The `async` keyword allows pymerlin to interleave the execution of your new activity with other activities, which is
important when activities can pause and resume at various times
:::

Let's define
two [parameters](https://nasa-ammos.github.io/aerie-docs/mission-modeling/activity-types/parameters/), `rate`
and `duration`, and give them default arguments. Parameters allow the behavior of an activity to be modified by an
operator without modifying its code.

```python
@Model.ActivityType
def collect_data(model, rate=0.0, duration=Duration.from_string("01:00:00")):
    pass
```

For our activity, we will give `rate` a default value of `10.0` megabits per second and `duration` a default value of
`1` hour using pymerlin's built-in `Duration` type.

Right now, if an activity of this type was added to a plan, an operator could alter the parameter defaults to any value
allowed by the parameter's type. Let's say that due to buffer limitations of our camera, it can only collect data at a
rate of `100.0` megabits per second, and we want to notify the operator that any rate above this range is invalid. We
can do this
with [parameter validations](https://nasa-ammos.github.io/aerie-docs/mission-modeling/activity-types/parameters/#validations)
by adding a method to our class with a couple of annotations:

```python
@Model.ActivityType
@Validation(lambda rate: rate < 100.0, "Collection rate is beyond buffer limit of 100.0 Mbps")
def collect_data(model, rate=0.0, duration=Duration.from_string("01:00:00")):
    pass
```

The `@Validation` decorator specifies a function to validate one or more parameters, and a message to present to the
operator when the validation fails. Now, as you will see soon, when an operator specifies a data rate above `100.0`,
Aerie will show a validation error and message.

Next, we need to tell our activity how and when to effect change on the `recording_rate` resource, which is done in
an [Activity Effect Model](https://nasa-ammos.github.io/aerie-docs/mission-modeling/activity-types/effect-model/). We do
this by filling out the body of the `collect_data` function.

For our activity, we simply want to model data collection at a fixed rate specified by the `rate` parameter over the
full duration of the activity. Within the `run()` method, we can add the follow code to get that behavior:

```python
@Model.ActivityType
@Validation(lambda rate: rate < 100.0, "Collection rate is beyond buffer limit of 100.0 Mbps")
def collect_data(model, rate=0.0, duration=Duration.from_string("01:00:00")):
    model.data_model.recording_rate += rate
    delay(duration);
    model.data_model.recording_rate -= rate
```

Effects on resources are accomplished by using one of the many static methods available in the class associated with
your resource type. In this case, `recording_rate` is a discrete resource, and therefore we are using methods from
the `DiscreteEffects` class. If you peruse the static methods in `DiscreteEffects`, you'll see methods
like `set()`, `increase()`, `decrease()`, `consume()`, `restore()`,`using()`, etc. Since discrete resources can be of
many primitive types (e.g. `Double`,`Boolean`), there are specific methods for each type. Most of these effects change
the value of the resource at one time point instantaneously, but some, like `using()`, allow you to specify
an [action](https://nasa-ammos.github.io/aerie-docs/mission-modeling/activity-types/effect-model/#actions) to run
like `delay()`. Prior to executing the action, the resource changes just like other effects, but once the action is
complete, the effect on the resource is reversed. These resource effects are sometimes called "renewable" in contrast to
the other style of effects, which are often called "consumable".

In our effect model for this activity, we are using the "consumable" effects `increase()` and `decrease()`, which as you
would predict, increase and decrease the value of the `recording_rate` by the `rate` parameter. The `run()` method is
executed at the start of the activity, so the increase occurs right at the activity start time. We then perform
the `delay()` action for the user-specified activity `duration`, which moves time forward within this activity before
finally reversing the rate increase. Since there are no other actions after the rate decrease, we know we have reached
the end of the activity.

If we wanted to save a line of code, we could have the "renewable" effect `using()` to achieve the same result:

```python
with using(model.data_model.recording_rate, rate):
    delay(duration)
```

With our effect model in place, we are done coding up the `collect_data` activity and the final result should look
something like this:

% :::{doctest})

% >>> print&#40;"This parrot wouldn't voom if you put 3000 volts through it!"&#41;)

% This parrot wouldn't voom if you put 3000 volts through it!!)

% :::)

:::{testsetup}
import pymerlin
:::

:::{testcode}
# Comment
pymerlin.checkout()
:::

:::{testoutput}
pymerlin checkout successful: All systems GO 🚀
:::

:::{testcode}
from pymerlin import MissionModel, Duration
from pymerlin._internal._decorators import Validation
from pymerlin.model_actions import delay

@MissionModel
class Model:
    def __init__(self, registrar):
        self.data_model = DataModel(registrar)

class DataModel:
    def __init__(self, registrar):
        self.recording_rate = registrar.cell(0)
        registrar.resource("recording_rate", self.recording_rate.get)

@Model.ActivityType
@Validation(lambda rate: rate < 100.0, "Collection rate is beyond buffer limit of 100.0 Mbps")
def collect_data(model, rate=0.0, duration="01:00:00"):
    model.data_model.recording_rate += rate
    delay(Duration.from_string(duration))
    model.data_model.recording_rate -= rate
:::

:::{testcleanup}
from pymerlin import simulate, Schedule, Directive
res = simulate(
    Model,
    Schedule.build(("00:00:01", Directive("collect_data", {"rate": 20.0, "duration": "00:00:01"}))),
    "01:00:00"
)
if str(res) != "({'recording_rate': [ProfileSegment(extent=+00:00:01.0000.0, dynamics=0.0), ProfileSegment(extent=+00:00:01.0000.0, dynamics=20.0), ProfileSegment(extent=+00:59:58.0000.0, dynamics=0.0)]}, [Span(type='collect_data', start=+00:00:01.0000.0, duration=+00:00:01.0000.0)], [])":
    print(res)  # This causes cleanup to fail
:::

Ok! Now we are all set to give this a spin.


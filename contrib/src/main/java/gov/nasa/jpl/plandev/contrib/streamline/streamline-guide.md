# Streamline Modeler's Guide

This guide is meant to help you find useful classes and methods in the streamline library,
and to suggest patterns, techniques, and tips to consider when writing models.

The guide is organized first by the structure of the streamline package itself, and second by use cases.
If you have a specific use case, it may first be worth checking the use cases, where you may find code examples
and ways that different methods and techniques work together.

## Library Structure

The library itself is split into the following top-level directories:
- `modeling` -
  A large collection of pre-built resource types and related utilities for modelers to use.
  __This is the primary directory of interest to most modelers.__
- `core` -
  A small collection of key interfaces and classes for the library itself, of little direct use to most modelers.
  This may be of interest to advanced users looking to extend the library with their own resource types.
- `debugging` -
  A suite of tools for debugging models that use the streamline library.
- `unit_aware` -
  A partial mirror of the modeling directory, which contains some support for unit-aware resources and related operations.
  Although this is committed, it's not as user-friendly or complete as the main modeling directory.
  Consider code in this directory like a beta or experimental version, likely to change in the future when someone
  finds a better way to integrate these ideas.
- `utils` -
  A small collection of miscellaneous utility classes used to write the library.
  There is very little of direct interest, though modelers may sometimes import a class from this package if it's used
  in the signature of another utility.

### Dynamics Types

The `modeling` package is then divided by resource dynamics. These are the major "kinds" of resources:
- `discrete` -
  The simplest kind of resource, which doesn't change over time unless it is changed by a task.
  These most closely mirror variables in a programming language, which change only when explicitly mutated by the code.
- `polynomial` -
  The next most commonly used resource, representing a state whose value is a polynomial in time.
  For example, one might describe forces in a kinematic simulation using discrete resources, then integrate to get
  velocity and position described by polynomials.
- `linear` -
  A strictly-linear polynomial, used for compatibility with the native Aerie registrar.
  Support for this type is limited, though the `asPolynomial` and `assumeLinear` functions allow you to go back and forth
  between linear and polynomial types with minimal overhead.
- `clocks` -
  A special-purpose resource type which directly uses Aerie's Duration type to represent time.
  Because they use the integral Duration class to represent time exactly the same way as the Aerie simulation engine,
  these clocks do not suffer precision issues the way a polynomial eventually would.
  Especially useful is the `VariableClock` type, which can be used to implement stopwatches and timers.
- `black_box` -
  Another special-purpose resource type, these describe resources whose dynamics are at least partially unknown or unrepresentable.
  These can be used to connect to external sources of information like SPICE kernels, or to represent complicated functions
  like an exponential decay model of RTG power.
  To get back to other kinds of dynamics, this package includes `Approximation` and related classes, which allow you to
  approximate complicated resource dynamics as simpler ones, most notably as discrete or linear dynamics so they can be
  registered as part of the simulation output.

### Dynamics package structure

Within each package, there are a few kinds of classes / interfaces:
- A dynamics class or interface -
  These will implement `core.Dynamics`, and describe a kind of resource.
  Of note is the `extract` method, which describes the current value, and the `step` method,
  which describes how the object changes as time passes.
- `*Resources` -
  Methods for constructing and deriving resources of that type.
  For example, `PolynomialResources` contains methods for building polynomial resources anew,
  and for deriving polynomial resources, e.g. by adding or multiplying other polynomial resources together.
- `*Effects` -
  Methods for applying effects to resources of that type.
  Effects are how you express that something "happened" in the simulation, as opposed to just time passing.
  For example, you may increment the value of a counter, double the value of a polynomial, or toggle a boolean.
- A `monads` folder (optional) -
  When present, this reflects that this kind of dynamics have some deeper structure we can leverage to write our
  code more clearly or easily. This generally isn't worth reading directly, though a few methods may be useful to
  intermediate users. Specifically, if there's a `*ResourceMonad`, its `map` methods may be useful,
  and if there's a `*DynamicsMonad`, it may have an `effect` method that can be useful.

## General advice

### Creating a new resource

We use a resource to track the state of a simulation.
Any state that can change over time should probably be in a resource, so Aerie can keep it synchronized with the rest
of the simulation (simulations can have parallel branches, so trying to manage state without a resource is likely to
hard-to-debug and pervasive errors).

Within the streamline framework, you need to make two independent choices to decide what type a new resource will be:
Whether it's mutable, and what dynamics type the resource will use.

#### Mutable Resources

Resources can either be `MutableResource` or just `Resource`.
This roughly means "read/writeable" or "readable" permissions.
Every `MutableResource` is a `Resource` (because read/writeable resources can be read),
but not every `Resource` is a `MutableResource`.

We use a `MutableResource` when a resource's value is determined only by "things happening" to it.
In contrast, a non-mutable `Resource` is usually defined through a formula involving other resources,
a process we call "deriving" a resource.
A derived resource is fully determined by the resources it's derived from.

For an example, imagine we want to model a battery, a fixed solar panel, and a device that draws power.
The device state might be a `MutableResource`, controlled by activities that turn the device on or off.
The device's power draw would be a `Resource` derived from its state.
The solar panel power output might be a `Resource` describing power output as a function of time,
perhaps taking into account the time of day to estimate solar incidence.
The battery state of charge would be a `Resource` derived from the resources above, probably through an integral.

If we instead wanted to model a movable solar panel, the solar panel orientation might be a `MutableResource`
controlled by activities, and the solar panel power output would be a `Resource` derived from that.

#### Resource Dynamics

Resource dynamics determine how a resource evolves over time, when nothing else is happening.
For example, a countdown timer might continuously run down towards zero,
or a battery state of charge might continuously increase as it charges.

The following types of dynamics are currently built-in to the streamline library to cover common use cases:
- `discrete` -
  Resources that don't change value just because time is passing.
  Discrete `MutableResource`s can still change when an effect is applied by a task,
  and discrete derived resources still change when the resources they're derived from change.
  
  Discrete resources can also have any type, from primitives like double or boolean, to complex user-defined classes.

- `polynomial` -
  Resources whose value is a number (a double), which changes over time according to a polynomial over time in seconds.
  There's special support for arithmetic, calculus, and comparisons on polynomial resources.
  Use polynomials to efficiently describe continuous processes, like power draw or data transfer,
  especially if integration is needed.

- `clock` -
  Resources whose value is a `Duration`, the native time type in Aerie.
  These clocks can move precisely in step with the simulation time.
  Over long periods of time, trying to track time with a `polynomial` may lose precision, but a `clock` will always be exact.

  Especially useful is the `VariableClock` type.
  A `MutableResource<VariableClock>` can be used as stopwatches and countdown timers,
  and methods in `VariableClockEffects` support this.

- `linear` -
  Resources whose value is a degree-1 polynomial.
  This type has limited support, mostly for compatibility with Aerie's `real` registrar method.
  We generally prefer to model things as polynomials, and use `assumeLinear` or approximations just to register them.

- `black_box` -
  Resources whose value changes in ways that are partially or completely obscure to the resource framework.
  These are a more niche kind of resource, but can be very powerful for some use cases.
  Given the choice, other modeling options are usually better than `black_box` methods.
  `black_box` dynamics cannot be registered as outputs from the simulation;
  instead, we need to make some `discrete` or `linear` approximation of them to register.
  There are tools in the `black_box` library to help with this, though it can be expensive to run these approximations.

  Some cases where it may make sense to use black-box methods include reading from external data sources like SPICE,
  or representing complex mathematical functions.

### Defining new effects

Sometimes, the effects available in the `*Effects` classes aren't enough to describe the behavior you're modeling.
For uncommon, one-off effects, the easiest thing to do is use `emit` and an `effect` helper, and write the effect in-line.
For discrete resources, the `DiscreteDynamicsMonad.effect` lets you describe the effect in terms of the value itself,
which is the easiest way to write a new effect.
For other dynamics, `DynamicsMonad.effect` lets you describe the effect in terms of the unwrapped dynamics,
which is the easiest general-purpose way to write a new effect.
Some examples:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.polynomialResource;

class Model {
    MutableResource<Discrete<String>> message = discreteResource("");
    MutableResource<Polynomial> poly = polynomialResource(0);
    
    public void testEffects() {
        message.emit(effect(s -> s + "!"));
        poly.emit(DynamicsMonad.effect(p -> p.multiply(polynomial(2))));
    }
}
```

This provides the minimum necessary to get the effect to work.
That said, it's good practice to include a name for the effect, for debugging it later.
For names without arguments, there's a convenient overload of `emit`, and for formatted names, we can use `Naming.name`.
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.polynomialResource;

class Model {
  MutableResource<Discrete<String>> message = discreteResource("");
  MutableResource<Polynomial> poly = polynomialResource(0);

  public void testEffects(String importantInfo) {
    message.emit("Exclaim!", effect(s -> s + "!"));
    poly.emit(name(
            DynamicsMonad.effect(p -> p.multiply(polynomial(2))),
            "Double, because %s",
            importantInfo));
  }
}
```

On the other hand, if this effect is meant to be a reusable, shared function, we recommend wrapping the effect in a
static utility function, similar to how `*Effects` classes are constructed.
This will let others use the effect on any compatible resource.
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;

class SpecialEffects {
    public void exclaim(MutableResource<Discrete<String>> resource) {
        resource.emit("Exclaim!", effect(s -> s + "!"));
    }
    
    public void rescale(MutableResource<Polynomial> resource, double scalingFactor) {
        resource.emit(name(DynamicsMonad.effect(p -> p.multiply(polynomial(scalingFactor))),
                "Scale by %s", scalingFactor));
    }
}
```

Finally, there are a few cases where the default behavior supplied by `effect`, namely to catch any errors that come up
when we apply the effect, and to attach an expiry of `NEVER`, may not be appropriate.
In these cases, the `bindEffect` method may be helpful, or you can construct the effect without a helper method.
For example,

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.*;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;

class SpecialEffects {
    // Note: This effect is a *bad* idea, it's only here to illustrate a highly unusual need and technique.
    // We should not generally be messing with expiries directly like this.
    // It's extremely easy to get yourself into infinite loops doing this.
    public <D extends Dynamics<?, D>> void makeExpireAt(MutableResource<D> resource, Duration expiry) {
        // The effect naturally gets an ErrorCatching<Expiring<D>>
        // Notice how we're using map to unwrap the outer layer, then taking apart the Expiring
        // in order to put it back together with a potentially earlier expiry.
        resource.emit(name((
                (ErrorCatching<Expiring<D>> d) -> d.map(
                        (Expiring<D> e) -> expiring(e.data(), e.expiry().or(Expiry.at(expiry))))),
                "Make expire no later than %s", expiry));
    }
}
```

### Defining new dynamics

This is a highly unusual use case, but one you may run into, especially if you're looking to define highly performant
and expressive utilities for your model.
Dynamics describe the evolution of a value _between effects_, so we should only be thinking about defining a new dynamics
type if we can't model something through existing dynamics types, daemon tasks, and effects.

The first and simplest thing to do to define a new dynamics type is to implement the `Dynamics` interface.
Let's say we want to implement a `LinearAttitude` dynamics type, representing an attitude in 3D with the `Rotation` class,
which rotates at a uniform rate about a fixed axis. Records are usually a good choice for dynamics implementations,
since dynamics are expected to be immutable and value-equals.
Then, we need to define the `extract` method to get the current value, and the `step` method to evolve the dynamics
over time.
Note that the streamline framework tries to minimize the depth of `step` calls, to reduce cumulative errors.
For example, rather than `step`ping three times, one minute each time, it would try to step once for 3 minutes total.
For this reason, there's little need to implement your own cumulative-error mitigations.
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

// Note that we can define extract just by naming that field in the record "extract".
record LinearAttitude(Rotation extract, Vector3D axis, double radianPerSecond) implements Dynamics<Rotation, LinearAttitude> {
    @Override
    public LinearAttitude step(Duration t) {
        var changeInAttitude = new Rotation(axis, radianPerSecond * t.ratioOver(SECONDS), RotationConvention.VECTOR_OPERATOR);
        return new LinearAttitude(changeInAttitude.applyTo(extract), axis, radianPerSecond);
    }
    
    // Note that records define equals and hashCode as value types (which is what we want),
    // but Rotations don't have a well-behaved value equality. Be cautious using a type like this.
}
```

At this point, we have just enough to start using these resources, but we don't have any `*Resources` derivation methods
nor `*Effects` effects methods for working with them.
While we could do all resource derivations and effects in-line, it's usually more legible to pull those out into utility
classes. Here are a few examples you might define:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.wheneverDynamicsChange;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.*;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.apache.commons.math3.geometry.euclidean.threed.RotationConvention.VECTOR_OPERATOR;

record LinearAttitude(Rotation extract, Vector3D axis, double radianPerSecond) implements Dynamics<Rotation, LinearAttitude> {
    @Override
    public LinearAttitude step(Duration t) {
        var changeInAttitude = new Rotation(axis, radianPerSecond * t.ratioOver(SECONDS), VECTOR_OPERATOR);
        return new LinearAttitude(changeInAttitude.applyTo(extract), axis, radianPerSecond);
    }
    
    public static LinearAttitude from(Rotation attitude, Vector3D axisAngle) {
        // It's a good idea to normalize your representation, if there are multiple ways to represent logically
        // equivalent values. We should be normalizing attitude as well, but that's harder than this example warrants.
        double angle = axisAngle.getNorm();
        if (angle < 1e-12) {
            axisAngle = new Vector3D(1, 0, 0);
            angle = 0;
        }
        return new LinearAttitude(attitude, axisAngle, angle);
    }
}

class LinearAttitudeResources {
    private LinearAttitudeResources() {}

    /**
     * Integrate a rotation rate, given in axis-angle format, to get a resulting attitude.
     */
    public static Resource<LinearAttitude> integrate(
            Resource<Discrete<Vector3D>> rotationRateAxisAngle,
            Rotation initialAttitude) {
        // Start off by pulling the current rotation rate and initial attitude
        MutableResource<LinearAttitude> integral = resource(
                LinearAttitude.from(initialAttitude, currentValue(rotationRateAxisAngle)));
        // Whenever the rotation rate change, update the integral.
        // Use bindEffect instead of effect, because we want to propagate error and expiry from integrand.
        // Use DynamicsMonad.map to automatically preserve the error and expiry from integrand.
        wheneverDynamicsChange(rotationRateAxisAngle, rate -> integral.emit("Update integrated attitude",
                bindEffect(attitude -> map(rate, rate$ -> LinearAttitude.from(attitude.extract(), rate$.extract())))));
        return integral;
    }
}

class LinearAttitudeEffects {
    private LinearAttitudeEffects() {}
    
    public static void stopRotation(MutableResource<LinearAttitude> attitude) {
        attitude.emit("Stop Rotation", effect(a -> LinearAttitude.from(a.extract(), Vector3D.ZERO)));
    }
    
    public static void jumpAttitude(MutableResource<LinearAttitude> attitude, Rotation delta) {
        attitude.emit(name(
                effect(a -> new LinearAttitude(delta.applyTo(a.extract()), a.axis(), a.radianPerSecond())),
                "Discretely change attitude by %s", delta));
    }
}
```

## Example Use Cases

### Simple Variables / Discrete Resources

Suppose you want something in a simulation that you can get and set like a variable in most programming languages.
Since the value will change over time in the simulation, we should use a resource.
Resources are managed by Aerie to stay synchronized with simulation time, even across parallel branches of the simulation.
(Don't worry if you don't understand why that's important yet.)

For this use case, the simplest thing we can use is a mutable discrete resource.
Mutable because we want to set new values, and discrete because we don't need the value to change over time, just when we set a new value.

Since we're looking to create a discrete resource, we look in the `DiscreteResources` class, and use the `discreteResource`
factory method.
Note that while we could use the more general `MutableResource.resource` and `Discrete.discrete`, the combined method
`discreteResource` will handle issues with floating-point precision automatically if the value is a double.

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;

class Model {
    // Preferred:
    MutableResource<Discrete<Integer>> counter = discreteResource(0);
    // Allowed, with caveats:
    MutableResource<Discrete<Integer>> counter = resource(discrete(0));
}
```

To change the value, we look to the `DiscreteEffects` class.
There's a `set` method if we just want to set the value, but there are also other methods for more specialized effects.
For example, there are `increment` and `decrement` methods to work with counters or a `toggle` method for booleans.

This primarily matters when effects happen in parallel.
The resource framework will automatically handle parallel effects by trying all consistent orderings;
if the results agree regardless of effect order, we say those effects "commute", and apply all of them.
Otherwise, the framework will raise an error.
By using the most descriptive effect we can, we minimize the failures caused by conflicting concurrent effects,
and perhaps more importantly, avoid incorrect results.

For example, here's one way we could model a counter:

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteDynamicsMonad.effect;

class Model {
    MutableResource<Discrete<Integer>> counter = discreteResource(0);

    // The "wrong" way to model this:
    public void badlyIncrementCounter() {
        set(counter, currentValue(counter) + 1);
    }

    // The "right" way to model this:
    public void incrementCounter() {
        increment(counter);
    }

    // The "right" way, if `increment` weren't already written:
    public void incrementCounter2() {
        counter.emit("increment", effect(n -> n + 1));
    }
}
```

To see why this matters, let's imagine two activities both try to increment the counter at the same time.
If they both call `badlyIncrementCounter`, then both of them read the current value of the counter (let's say it starts at 0),
both add 1 to that value, and both try to set the new value to 1.
Since this is the same value, the effects commute, and the counter is set to 1, but this is likely not what we want!

Alternatively, if both activities had called `incrementCounter`, then the counter would end up with value 2.
This is because `increment` moves "reading" the resource into the effect itself.
When we apply both effects, the second effect to be applied "reads" _after_ the first effect is applied.

Finally, `incrementCounter2` demonstrates how to write a new effect on a discrete resource.
In general, any time you would read a resource, do something with that value, then write to the resource again,
you should instead consider making that entire process a single effect.
Adding a name for the effect is optional, but can help with debugging later.

Currently, the discrete resource types which we've added extra support for in `DiscreteEffects` are:
* Flags - `MutableResource<Discrete<Boolean>>`
* Counters - `MutableResource<Discrete<Integer>>`
* Queues - `MutableResource<List<T>>`
* Consumable and nonconsumable quantities - `MutableResource<Discrete<Double>>`

Other types are fully supported as well, but you may need to write effects yourself,
like in the `incrementCounter2` example above.

### Integration and Linear Resources

One common behavior in simulation is integration.
For example, a data rate may be defined by the instruments, while the stored data volume is some integral of that rate.
Fortunately, basic calculus is built into the framework using `PolynomialResources`.
For example:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.asPolynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.integrate;

class Model {
    MutableResource<Discrete<Double>> dataRateInBitsPerSecond = discreteResource(0.0);
    Resource<Polynomial> storedDataVolumeInBits = integrate(asPolynomial(dataRateInBitsPerSecond), 0);
}
```

Here, we're modeling the data rate as a mutable discrete resource.
Since integration expects a polynomial resource, we can use the `asPolynomial` method to convert it.
Then, the `integrate` method creates a new polynomial resource, whose value will be the integral over time in seconds
of the data rate, aka. the data volume.
Note that when the data rate is positive, the data volume will climb continuously over time, without needing to take
any samples or run any tasks to update it.
This ability to change over time without emitting effects separates discrete resources from other kinds of resources.

In our case, we know because the integrand is discrete that the integral will be linear.
There is a dynamics type called `Linear`, but we generally favor `Polyomial`.
`Linear` exists primarily for compatibility with the Aerie registrar.
To get from `Linear` to `Polynomial`, we can use `asPolynomial$`.
To go the other way, we can use `assumeLinear` if we know that the `Polynomial` will be linear in practice,
like in the example above.
Otherwise, we'll need to use approximations to represent higher-order polynomials as a sequence of linear segments,
but those approximations are beyond the scope of this section.

One important variation of integration is "clamped" integration.
This happens when there are finite limits to the integral's value.
For example, the amount of stored data can't be negative, and can't exceed the capacity of the storage device.
The `clampedIntegrate` method will respect these limits, and will also report over- and underflow when limits are hit.
Our data model from before might be more accurately modeled like so:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;

class Model {
    MutableResource<Discrete<Double>> dataRateInBitsPerSecond;
    Resource<Polynomial> dataVolume;
    Resource<Polynomial> droppedData;

    public Model() {
        dataRateInBitsPerSecond = discreteResource(0.0);
        var result = clampedIntegrate(
                asPolynomial(dataRateInBitsPerSecond),
                constant(0),
                constant(1e9),
                0);
        dataVolume = result.integral();
        droppedData = integrate(result.overflow(), 0);
    }
}
```

### Timers and Stopwatches

Another common task in simulations is to trigger events after a fixed amount of time (timers) or to measure how long
something takes to happen (a stopwatch). While we could use an integral of the constant 1 to measure this approximately,
this could suffer precision issues when measuring long periods of time because polynomial resources use `double`s.
A more precise and convenient option is to use the `Clock` and `VariableClock` resource types.

A `Clock` is a kind of resource that runs exactly in sync with simulation time.
These are used within the framework, but infrequently used in models.
A `VariableClock` is a resource that runs at an integer multiple of simulation time, and is commonly used in models.
In particular, a `VariableClock` that runs at either 1x (normal) or 0x (stopped) can function as a stopwatch,
and a `VariableClock` that runs at either -1x (backwards) or 0x (stopped) can function as a countdown timer.
For an example of both:

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClock;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.set;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClock.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockEffects.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockResources.lessThanOrEquals;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.constant;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.when;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.waitUntil;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

class Model {
  MutableResource<VariableClock> stopwatch = resource(pausedStopwatch());
  MutableResource<VariableClock> timer = resource(runningTimer(Duration.of(5, MINUTES)));

  public void event1() {
    // Start the stopwatch from 0
    restart(stopwatch);
    // Reset the timer
    set(timer, pausedTimer(Duration.of(5, MINUTES)));
  }

  public void event2() {
    // Pause the stopwatch at its current value
    pause(stopwatch);
    // Wait for the timer to go off
    waitUntil(when(lessThanOrEquals(timer, constant(ZERO))));
  }

  public void event3() {
    // Start the timer from its current value
    startCountdown(timer);
  }
}
```

### Arithmetic and other Derived Resources

In [the section on integration](#integration-and-linear-resources), we saw how the value of the integral resource is
determined entirely by the behavior of the rate resource.
In fact, there are many cases where one resource is derived from others.
In these cases, we can look in `*Resources` classes for helpful methods.
Some of those methods are listed below, but please consult the code itself for the most up-to-date and complete list.

#### All Resources

There are a few derivation methods that are generic to all resources, in the `Resources` class:
- `cache` builds a resource identical to its argument, except that it stores its value rather than recomputing it
  each time it's sampled. Since storing that value incurs some overhead, recalculation is usually faster.
  Use this method when profiling suggests a particular resource's sampling is a performance bottleneck.
- `shift` builds a resource that is identical to its argument from a fixed duration ago.
- `forward` watches an arbitrary resource and sets a mutable resource to match it.
  This is sometimes needed, especially when building complex derived resource structures with feedback loops,
  but omitting `forward` and just using the resource with the desired value directly is almost always preferred.
- `reduce` applies an operator to a collection of resources. While generic, this can be inefficient.
  Most `*Monad` classes also have a `reduce` operator, which will be more efficient because they leverage knowledge about
  that particular kind of resource.
- `eraseExpiry` returns a resource identical to this one, except it never expires.
  This is a specialized method useful primarily when building complex derived resource structures with feedback loops.
- `signalling` is a highly specialized method used to convert expiry information into effects, legible by Aerie.
  This is needed when a resource derivation might inject an "early" expiration, and is rarely needed in model code directly.
  Rather, it's used in library methods like `PolynomialResources.greaterThan`.

#### Polynomial Resources
The most built-up of these is `PolynomialResources` (at the time of writing), which includes:
- `add`, `subtract`, and `multiply` between two `Resource<Polynomial>`s, and vararg versions `sum` and `product`
- `divide`, which divides a `Resource<Polynomial>` by a `Resource<Discrete<Double>>` (because the quotient of
  two polynomials isn't necessarily a polynomial)
- `integrate` and `clampedIntegrate`, as previously described
- `differentiate`, the inverse of `integrate`
- `movingAverage` (experimental)
- Comparators `greaterThan`, `greaterThanOrEquals`, `lessThan`, and `lessThanOrEquals` between a `Resource<Polynomial>`
  and a `double` or between two `Resource<Polynomial>`s, [also described here](#comparisons-and-thresholds)
- `min` and `max` between two or more `Resource<Polynomial>`s
- `abs`, the absolute value function
- `clamp`, which combines min and max to clamp a `Resource<Polynomial>` between lower and upper `Resource<Polynomial>`s
- `binned`, which returns a discrete label for which "bin" a `Resource<Polynomial>` is currently in.
- Experimental unit-aware versions of most of the above.
For the most up-to-date list, check `PolynomialResources` itself.

#### Discrete Resources

There are fewer pre-made derivation methods for discrete resources, because it's easier to write our own (more on that later).
At the time of writing, the derivation methods that exist in `DiscreteResources` include:
- `cache`, a version of the more generic `Resources.cache` which takes an `updatePredicate`.
  This function takes the currently cached value and a new value and decides whether to update the cache.
  This can be used to apply tolerances to cached values, filtering upstream updates to reduce downstream re-work.
  This can also be used to cache discrete resources when the value has a poorly-behaved `equals` method.
- `sampled` to periodically sample a value from an arbitrary source
- `precomputed` to iterate through a list of values at predetermined times
- `equals` and `notEquals`, returning `Resource<Discrete<Boolean>>`
- `and`, `or`, and `not` for discrete boolean resources, and their vararg counterparts `all` and `any`.
  Note that these will short-circuit where possible to reduce computation when sampling resources.
- `choose`, a resource-level ternary (if-then-else) operator.
  This switches between the value of the "then" or "else" resource (which don't need to be discrete), based on the value
  of a discrete boolean "condition" resource.
  Like `and` and `or`, this resource will short-circuit, evaluating only one of the "then" or "else" resources each sample.
  Hence, this can be used to do run-time performance trade-offs, switching between high-cost high-fidelity resources
  when needed and low-cost low-fidelity resources when acceptable.
- `assertThat`, a resource which asserts that its argument is always true.
  If the argument is ever false, the resource switches into an error mode.
  Registering an assertion, or a resource derived from an assertion, will either log or throw that assertion error
  (configurable in the registrar).
- `add`, `subtract`, `multiply`, and `divide` for `Resource<Discrete<Double>>`, and vararg counterparts `sum` and `product`.
  Also `addInt`, `subtractInt` etc. for `Resource<Discrete<Integer>>`.
- `isEmpty` and `isNonEmpty` for discrete collection resources.
- `contains` between a discrete collection resource and a discrete value resource.

Again, check `DiscreteResources` for the most up-to-date list of available functions.

For discrete resources in particular, we can also easily define an arbitrary derived resource using the
`DiscreteResourceMonad.map` function. This takes one or more discrete resources, and a function on their values, and
returns a discrete resource whose value is the result of that function at all times. For example,
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;

class Model {
  MutableResource<Discrete<List<String>>> names;
  Resource<Discrete<String>> nameOfInterest;
  Resource<Discrete<Integer>> indexOfInterest = map(names, nameOfInterest,
          (List<String> names$, String nameOfInterest$) -> {
            // Note that arbitrary code can go here, including complex logic.
            for (int i = 0; i < names$.size(); ++i) {
                if (StringUtils.equalsIgnoreCase(names$.get(i), nameOfInterest$)) {
                    return i;
                }
            }
            return -1;
          });

  // Discrete resources can use any data type.
  Resource<Discrete<Double>> paramA;
  Resource<Discrete<Integer>> paramB;
  Resource<Discrete<String>> paramC;
  Resource<Discrete<Map<String, MyRecord>>> paramD;
  record MyRecord(int x, double y) {}

  // map can take a method handle instead of a lambda, a common way to organize model code
  Resource<Discrete<Boolean>> result = map(paramA, paramB, paramC, paramD, Model::deriveResult);

  private static boolean deriveResult(double a, int b, String c, Map<String, MyRecord> d) {
      // IMPORTANT! Take the resource values you depend on as parameters to this method.
      // Do *not* call currentValue(someResource) here, as it may break the derivation logic and be less efficient.
      return true;
  }
}
```

### Comparisons and Thresholds

Another common task is to determine when a resource has reached some limit.
In general, there are two reasons we might care about this:
1. To report that fact, or a value derived from it, as a resource.
2. To run a task when that happens.

In both cases, we should start by defining a `Resource<Discrete<Boolean>>` that tells us when we've reached the limit.
To do this, we should check the appropriate `*Resources` class for methods that might compare this resource.
For discrete resources, we may need to use `DiscreteResourceMonad.map` to write the comparison ourselves.
For example:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.greaterThanOrEquals;

class Model {
  Resource<Polynomial> dataVolume;
  Resource<Discrete<Integer>> counter;

  Resource<Discrete<Boolean>> dataIsFull = greaterThanOrEquals(dataVolume, 1e9);
  Resource<Discrete<Boolean>> counterIsNegative = map(counter, n -> n < 0);
}
```

Once we have a boolean resource indicating whether a limit is reached or not, we may want to derive another value from it.
Of these, one of the most useful methods is `choose`, which acts like a resource-level ternary operator. For example,
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.choose;

class Model {
    Resource<Discrete<Boolean>> dataIsFull;
    // These could be derived from a complicated formula and vary over time.
    Resource<Polynomial> highOutgoingDataRate;
    Resource<Polynomial> lowOutgoingDataRate;

    // This resource will exactly equal whichever data rate is selected by dataIsFull at that time.
    Resource<Polynomial> outgoingDataRate = choose(dataIsFull, highOutgoingDataRate, lowOutgoingDataRate);
}
```

Another thing we may want to do is react to the resource first reaching some limit by running some task.
The `Reactions` class contains a variety of methods for this kind of situation, and is optimized to run tasks that do a
small amount of work each time they run, but may be triggered many times during a single simulation.
Some `Reactions` methods will take a discrete boolean resource directly, but all of them will accept a `Condition`,
which is Merlin's native way of describing when an event of interest happens.
The `when` method converts a discrete boolean resource into a `Condition` that fires when the resource is true.
For example:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Reactions;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.not;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.when;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.waitUntil;

class Model {
    Resource<Polynomial> dataVolume;

    public Model() {
        var dataIsFull = greaterThanOrEquals(dataVolume, 1e9);
        Reactions.whenever(dataIsFull, () -> {
            // React to hitting this limit
            sendWarningMessage();
            limitIncomingData();
            boostRadioPower();
            // Wait until we fall below the limit before we react again.
            // If we don't wait here, dataIsFull will still be true when we exit, and we'll loop forever.
            waitUntil(when(not(dataIsFull)));
        });
    }
}
```

### Daemons: Reactions and Periodic Tasks

Sometimes, models need to run tasks outside the context of any activity.
Generally, these tasks are either run at regular time intervals, or in reaction to some condition.
We've seen reactions to a condition [in another section](#comparisons-and-thresholds),
so we'll show an example of periodic activity here, using `Reactions.every`:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.Reactions;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOURS;

class Model {
    public Model() {
        Reactions.every(Duration.of(3, HOURS), () -> doSomething());
    }
}
```

For a more advanced example, let's suppose the time between tasks is controlled by a resource.
For example, it may be computed to sample an expensive modeling routine more frequently during a critical period,
and less frequently otherwise, to balance performance and simulation fidelity.
There are two main ways to accomplish this, demonstrated below:

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClock;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockEffects;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockResources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.every;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Reactions.whenever;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClock.runningStopwatch;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockEffects.restart;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClockResources.greaterThanOrEquals;

class Model {
    public Resource<Discrete<Duration>> samplePeriod;

    public Model() {
        // One way to do this:
        // In this version, the sample period is observed each time the loop starts.
        every(() -> currentValue(samplePeriod), () -> takeSample());

        // Another way to do this:
        // By using a timer and a condition, sample period is observed whenever it changes.
        // In particular, if samplePeriod drops, the loop will run sooner on this iteration.
        // This version is usually better, but incurs a small additional overhead to operate the timer.
        MutableResource<VariableClock> timer = resource(runningStopwatch());
        whenever(greaterThanOrEquals(timer, samplePeriod), () -> {
            takeSample();
            restart(timer);
            // Note that restarting the timer will make timer < samplePeriod, so no need to put an additional wait here.
        });
    }
}
```

Finally, there are a few methods in the `Reactions` class that are often useful:
- `wheneverDynamicsChange` to run a task whenever a resource changes value
- `wheneverUpdates`, which tries to run a task whenever there's an effect on a resource, including effects which don't
  change the resource value. This is less stable than `wheneverDynamicsChange`, but can be used with dynamics that don't
  have a well-behaved `equals` method.
- `whenever` to run a task whenever a `Condition` fires, or equivalently, whenever a discrete boolean resource is true.

Note that discrete boolean resources and `Condition`s are equivalent, and that equivalence is realized by
`DiscreteResources.when`, which builds a `Condition` that fires when a resource is true.
For that reason, methods that could reasonably return a condition should almost always be written to return a boolean
resource instead, as it's easier to go from a boolean resource to a condition than the converse.

### Affecting a Resource

We've seen [before](#simple-variables--discrete-resources) how to set a new value for a resource, and shown some more
complex effects on discrete resources. Effects are not limited to discrete resources, though.

While it's always possible to write your own effects, the framework comes with a selection of effects already built.
In particular, for polynomial resources, we have the `PolynomialEffects` class. At the time of writing, this contains:
- `consume`, `consuming`, `restore`, and `restoring`, to increase or decrease a polynomial resource.
  This can be done instantly, over time, or while some action is being performed.
  The change in value persists after the time or action is done.
  These methods are so named to evoke the idea of "consumables" in other simulation systems, like fuel or battery charge.
- `using` and `providing`, to increase or decrease a polynomial resource temporarily.
  This can be done for a fixed amount of time, or while some action is being performed.
  The change in value is reverted after the time or action is done.
  These methods are so named to evoke the idea of "nonconsumables" in other simulation systems, like power draw or data rate.

For example:
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.polynomialResource;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

class Model {
    MutableResource<Polynomial> fuelInKg = polynomialResource(100);
    MutableResource<Polynomial> bandwidthInKbps = polynomialResource(1024);
    
    public void doTurn() {
        // Style 1: Diminish bandwidth while the code inside is running, a bit like "with" context blocks.
        using(bandwidthInKbps, 10, () -> {
            // Style 2: Instantaneous effect (only for consumables) - reduce fuel by 5kg
            consume(fuelInKg, 5);
            delay(20, MINUTES);
            var burnTime = Duration.of(5, MINUTES);
            // Style 3: Effect over time, applied in the background - reduce fuel by 0.03kg per second for 5 minutes.
            consume(fuelInKg, 0.03, burnTime);
            // Note: When control hits this line, the 5 minute burn started at the previous line has *not* finished.
            // We could do other things here while that's happening, but instead we'll just wait for it.
            delay(burnTime);
        });
        // Note: When control hits this line, everything in the lambda has executed (including the 25 minutes of delay)
        // and the `using` will revert bandwidth back up.
    }
    
    public void nestedComms() {
        // Note that nonconsumable effects revert *only* the portion of the change they're responsible for.
        // If we kick off three usings in the background like this:
        using(bandwidthInKbps, 128, Duration.of(10, MINUTES));
        delay(3, MINUTES);
        using(bandwidthInKbps, 64, Duration.of(10, MINUTES));
        delay(3, MINUTES);
        using(bandwidthInKbps, 32, Duration.of(10, MINUTES));
        // Then the resulting profile would look something like this:
        //             ┌───┐          
        //          ┌──┘   │          
        //          │      │          
        //       ┌──┘      │          
        //       │         └──┐       
        //       │            │       
        //       │            └──┐    
        // ──────┘               └───
    }
}
```

Note that effects are something done _to_ a resource, and not inherently part of the resource.
That means that modeling a consumable or nonconsumable in Aerie is a matter of convention, and there's nothing strictly
preventing you from applying nonconsumable effects to a "consumable" resource, or vice versa.
This was an intentional choice to keep the number of types down and keep modeler flexibility up.
It also means that modelers can use effects that weren't imagined when a resource was first implemented, without needing
to rework the model to accommodate it.
For example, we might originally model battery power (not charge, but the maximum current the battery can supply)
as a nonconsumable resource. Turning on a device uses power, which is then relinquished when the device turns off.
Later, we decide to model partial battery failures, where the maximum current it can supply is diminished.
While we could "fake" a consumable change (permanently lowering the available current) by applying a nonconsumable change
with a very long duration, we get a cleaner solution by just applying the semantically correct consumable change.

### Getting a Resource Value

It may seem odd to put this section so late, but this is intentional.
It's often not necessary to directly get the value of a resource, and getting the value where it's inappropriate can
lead to a brittle or inefficient simulation.
We should only directly get a resource's value from a task, when other methods like reactions aren't appropriate.
In particular, we generally should not directly get a resource's value when defining a derived resource,
because this sidesteps the other layers of wrapping that resources apply.
<!-- TODO: link to resource monad description -->

To get the resource value, call `Resources.currentValue` on it. This method is generic to all resource types.
There's also a variant that will return a default value rather than throw an error if the resource is in error.

We can also call `Resources.currentData` to get a resource's current dynamics data, and a version that supplies a
default value in the case of error. This would tell us the value now and progressing into the future,
though if we care about this, we often care about the expiry and error information as well.

The most granular way of getting a resource value is to call `Resource.getDynamics()`, the instance method on all resources.
This will return the current fully-wrapped dynamics object for that resource, so cannot fail. It's then up to the caller
to unwrap this object as needed, layer by layer, to access the values they care about.

### Controllable Flow Network

This problem combines techniques from several previous sections. First, the problem statement:
> Model a flow network of nodes, each with a limited capacity and limited maximum output rate.
> Further, make each node's output controllable, such that it can be turned on or off.
> If a node is full and its inflows exceed its maximum outflows, it "spills" that additional inflow.
> Track how much each node spills as well.

To do this, let's first write a class modeling a single node.
The limited capacity suggests that `clampedIntegrate` should do the heavy lifting here.
Since we're using an integral, polynomial resources are a natural choice for the flows and volumes.

Since we're enclosing some resources in a reusable class, we need to think about how to organize the inputs to this class.
In general, we have two options: We can pass resources into the class constructor, or we can build that resource in the
class itself and expose it (directly or through accessor methods) to the rest of the model.

Taking resources in the class' constructor is better when those resources are generally derived from other resources in
the model. Whatever constructs this class will be responsible for building such resources, so those resources function
like the "glue" between the model components.

Building a resource in the class and exposing it for other components to affect makes it more like a private variable,
and is appropriate when that resource is primarily driven by tasks, including activities, and not primarily by other
states in the model.

In this case, it sounds like the inflow is derived from other nodes' outflows, so we'll accept it in the constructor.
By contrast, the output control sounds more like a private state of this node, so we'll construct it in the node and
add methods to the node class to turn the output on and off.
```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.turnOff;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.turnOn;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.choose;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;

class ControllableFlowNode {
    private final MutableResource<Discrete<Boolean>> outflowEnabled;
    public final Resource<Polynomial> storedVolume;
    public final Resource<Polynomial> spilledVolume;
    public final Resource<Polynomial> outflow;

    public ControllableFlowNode(
            Resource<Polynomial> inflow,
            double capacity,
            double maxOutflowRate
    ) {
        outflowEnabled = discreteResource(true);
        Resource<Polynomial> attemptedOutflowRate = choose(outflowEnabled,
                constant(maxOutflowRate),
                constant(0.0));
        // We attempt to outflow at the maximum rate. Clamping will reduce this outflow if needed.
        Resource<Polynomial> attemptedNetFlow = subtract(inflow, attemptedOutflowRate);
        var result = clampedIntegrate(attemptedNetFlow, constant(0.0), constant(capacity), 0);
        storedVolume = result.integral();
        spilledVolume = integrate(result.overflow(), 0);
        // Underflow is the amount by which the attempted outflow rate was reduced to actual outflow.
        outflow = subtract(attemptedOutflowRate, result.underflow());
    }

    public void enable() {
        turnOn(outflowEnabled);
    }

    public void disable() {
        turnOff(outflowEnabled);
    }
}
```

Now, let's set up a network with these nodes. In particular, let's set up a network like this:
```text
                   ┌───────────────┐            ┌───────────────┐           ┌───────────────┐             
                   │ Node A        │  1.5       │ Node D        │  1.0      │ Node F        │  2.0        
   Input A  ──────►│ Capacity: 10  ├───────────►│ Capacity: 50  ├─────┬────►│ Capacity: 80  ├───────►     
                   └───────────────┘            └───────────────┘     │     └───────────────┘             
                                                                      │                                   
                   ┌───────────────┐            ┌───────────────┐     │                                   
                   │ Node B        │  2.0       │ Node E        │     │                                   
   Input B  ──────►│ Capacity: 20  ├─────┬─────►│ Capacity: 50  ├─────┘                                   
                   └───────────────┘     │      └───────────────┘ 2.5                                     
                                         │                                                                
                   ┌───────────────┐     │                                                                
                   │ Node C        │     │                                                                
   Input C  ──────►│ Capacity: 15  ├─────┘                                                                
                   └───────────────┘  1.5                                                                 
```

We'll make all three inputs to the network mutable polynomial resources for now.
We'll also integrate the final output of the network, so we can track how much flow in total is passed through the network.

```java
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;

class Model {
    public final MutableResource<Polynomial> inputA, inputB, inputC;
    public final ControllableFlowNode nodeA, nodeB, nodeC, nodeD, nodeE, nodeF;

    public Model(Registrar registrar) {
        inputA = polynomialResource(0);
        inputB = polynomialResource(0);
        inputC = polynomialResource(0);

        nodeA = new ControllableFlowNode(inputA, 10, 1.5);
        nodeB = new ControllableFlowNode(inputB, 20, 2.0);
        nodeC = new ControllableFlowNode(inputC, 15, 1.5);
        nodeD = new ControllableFlowNode(nodeA.outflow, 50, 1.0);
        nodeE = new ControllableFlowNode(add(nodeB.outflow, nodeC.outflow), 50, 2.5);
        nodeF = new ControllableFlowNode(add(nodeD.outflow, nodeE.outflow), 80, 2.0);

        Resource<Polynomial> totalNetworkFlowVolume = integrate(nodeF.outflow, 0);

        registrar.real("inputA", assumeLinear(inputA));
        registrar.real("inputB", assumeLinear(inputB));
        registrar.real("inputC", assumeLinear(inputC));
        registerNode(registrar, "nodeA", nodeA);
        registerNode(registrar, "nodeB", nodeB);
        registerNode(registrar, "nodeC", nodeC);
        registerNode(registrar, "nodeD", nodeD);
        registerNode(registrar, "nodeE", nodeE);
        registerNode(registrar, "nodeF", nodeF);
        registrar.real("totalNetworkFlowVolume", assumeLinear(totalNetworkFlowVolume));
    }

    private void registerNode(Registrar registrar, String nodeName, ControllableFlowNode node) {
        registrar.real(nodeName + "/StoredVolume", assumeLinear(node.storedVolume));
        registrar.real(nodeName + "/SpilledVolume", assumeLinear(node.spilledVolume));
        registrar.real(nodeName + "/Outflow", assumeLinear(node.outflow));
    }
}
```

Finally, let's mock up an activity that adjusts some of these resources' states.

```java
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.providing;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;

class Activity {
    @ActivityType.EffectModel
    public void run(Model model) {
        model.nodeB.disable();
        model.nodeE.enable();
        // Increase inputA by 1.2 for the next 5 minutes
        providing(model.inputA, 1.2, Duration.of(5, MINUTES));
    }
}
```


## Background

This section is a reference, and you don't need to read it to start using the library.
However, some more advanced use cases benefit from understanding the framework more deeply.

### Resource Stack

Most resources comprise 4 levels of wrapping, each adding a different concept to the idea of a resource:
* A "Resource" encloses multiple dynamics segments. Each dynamics segment is...
* An "ErrorCatching" wrapper handling failures in the resource network, around...
* An "Expiring" wrapper that tracks when dynamics data is no longer accurate, around...
* Dynamics, which describe the evolution of a resource value over time.

Starting at the bottom is "dynamics" or "dynamics data", realized by the `Dynamics` interface.
This wraps a value with it's autonomous evolution over time (as opposed to changes induced by effects).
For example, `Discrete` applies no autonomous evolution, and can wrap any kind of value.
For another example, `Polynomial` evolves a `double` according to a polynomial in time.

The next layer is expiration, realized by the `Expiring` interface.
This pairs dynamics data with the latest time that this data is accurate.
We track this for all resource dynamics, because expirations propagate when deriving one resource from another.
Doing this propagation automatically helps the simulation to be correct and efficient.

The next layer is error-catching behavior, realized by the `ErrorCatching` interface.
This can either be the expiring-dynamics it's wrapping or a caught exception.
Resources can "fail" when deriving a resource (consider sampling `Y = sqrt(X)` when `X` is negative),
or when emitting effects (consider applying `n -> sqrt(n)` to `X` above).
This is especially common for concurrent effects (consider applying `n -> 2*n` and `n -> 3 + n` concurrently).
The errors thrown in these situations are caught by this layer, rather than crashing the entire simulation immediately.
These errors propagate to derived resources automatically, and the registrar has the option to log them rather than
crashing the simulation. That way, the simulation can continue in a partially-failed state, and save whatever results
it was able to calculate.

The final layer is the resource itself, realized by the `ThinResource` interface,
so named because the `Resource` interface combines this and the prior two layers.
The resource layer is a stable reference across multiple dynamics segments.
That is, dynamics describe a resource's value _between effects_ on that resource,
while the resource itself is preserved _across effects_.

For example, a polynomial resource may have initial dynamics `2t + 4`, i.e., a value of 4 growing at 2 per second.
After 5 seconds, those dynamics have evolved to `2t + 14`, but they are the "same" dynamics in some sense.
If we emit an effect that changes the dynamics to `-t + 5`, the resource is still the same.

### Monads

Mirroring the layered structure of the resources are a collection of monads.
These monads reflect the idea that operations on lower levels "naturally" extend to operations at higher levels.
Put another way, the behaviors and data introduced in each wrapping layer are largely independent of the data being wrapped,
and we should be able to handle wrapper data largely independent of data being wrapped.

For our purposes, a monad is a "type wrapper", a function which takes a type (not an object, the type itself)
and returns another type.
For example, the Java `Optional<T>` interface is a monad. It "wraps" a type like `String` into `Optional<String>`.
We'll use `optionalStr` throughout this section to mean an `Optional<String>` object.

The most motivating method in a monad is `map`, which applies a function to the wrapped argument in the "natural" way.
For example, `optionalStr.map(String::length)` will compute the length of the wrapped string.
If we were to diagram out the types, we might draw this:
```text
      map f
 M A ─────────► M B
        ▲
        │
        │ map
        │
        │
   A ─────────► B
        f
```
That is, `map` itself acts on a function `f : A -> B`, lifting it to a function `f : M A -> M B` where both the argument
and output are wrapped by the monad. In the Java example, the `.map(String::length)` bit is analogous to `map f`,
and we can think about this as just an odd notation for applying this function to the object `optionalStr`.

Sometimes, however, the function itself introduces the monad as well. For example, suppose we wrote a function called
`removeSuffix : String, String -> Optional<String>`, which returns the argument without a given suffix. If the argument
doesn't actually have that suffix, it returns an empty optional instead.
To apply this to `optionalStr`, we use a function called `bind`, or `flatMap` as it's usually named in Java:
`optionalStr.flatMap(s -> removeSuffix(s, ".txt"))`.

The key feature of `bind` is that it "squashes together" the monad wrapper of the argument with the monad wrapper of the
output, such that the final result has only one wrapper. In a diagram,
```text
      bind g
 M A ────────► M B
          ▲   ▲
      bind│  /
          │ /
           /
          /
         /
        / g
       /
      /
   A
```
That is, `bind` takes a "diagonal" function `g : A -> M B` and lifts just the argument to a monadic value.

If we had instead used `map` on `g`, we'd get a function `map g : M A -> M (M B)`, returning a "double-wrapped" value.
We'd then need a function to "squash together" those wrappers. This has a name, called `join`, though it doesn't have an
implementation in Java's `Optional` type. As a diagram:
```text
            M (M B)
              ▲│
             / │
            /  │
     map g /   │ join
          /    │
         /     │
        / ▲    │
       /  │    │
      /   │    ▼
 M A      │    M B
          │   ▲
      map │  /
          │ /
           /
          /
         /
        / g
       /
      /
   A
```
Indeed, it's always true that `bind` is equal to `join . map`, and that's how it's often defined in the streamline
framework. It's also possible to define a monad in terms of bind, but this leads to a less efficient framework,
and makes it harder to combine layers (more on that below).

If we're looking at the diagram above, there's one direction of arrow missing, one that takes a regular value and wraps
it. This is called `pure` (among other names), realized as `Optional.of()`.
`pure` is meant to wrap a value in the most "natural" wrapper, something that behaves like an unwrapped value.
In the diagrams above, `pure` would join `A` to `M A`, `B` to `M B`, and `M B` to `M (M B)`.

In the diagrams above, I've intentionally drawn all operations on a grid with base types on one axis
and layers of monadic wrapping on the other, so that the operations of a monad each have a particular "shape" on that grid.
As a rule, combining the monadic operations above in ways that produce the same final "shape" on this grid should produce
the same final operation. So, for example,
```text
  M A ────────► M B
               ▲▲
              / │
    pure . f /  │
            /   │
           /    │ pure
          /     │
         /      │
        /       │
       /        │
    A ────────► B
         f
```
In this diagram, we see how composing `pure` with `f` gives us a "diagonal" function. Apply `bind` to this would yield
a function from `M A` to `M B`. Similarly, `map f` goes from `M A` to `M B`, and indeed `map f = bind (pure . f)` is
always true.

The next thing to consider is functions with multiple arguments. Suppose `h : A, B -> C` takes two arguments, and we
want to apply it correctly to arguments of types `M A` and `M B`. In Java, suppose we have two `Optional<String>`s and
we want to concatenate them. We might do this: `optionalStr1.flatMap(s -> optionalStr2.map(t -> s + t))`.
If we translate this to `map` and `bind` calls, we get something like
`(bind (s -> map(t -> s + t) (optionalStr2))) optionalStr1`.
It's a little obscure, but what we're doing is "capturing" one argument of the concatenation in the lambda function.
This suggests that we should actually see the multi-argument `h` as a curried single-argument function `h : A -> B -> C`.
From there, we can rebuild the function we need like so:
- Apply `map` "on the inside": `a -> map (h a) : A -> (M B -> M C)`
- Then `bind` the other argument: `ma -> mb -> bind (a -> map (h a) mb) ma : M A -> M B -> M C`

This technically works, and in practice it's not too hard to apply it, but we can do better.
Suppose instead, we naively applied `map` to `h` directly. We'd get `map h : M A -> M (B -> C)`.
This is almost what we want, we just need a way to "distribute" the `M (B -> C)` to a `M B -> M C`.
The function which does this is named `apply`, and while we could define it in terms of the methods above, it's often
more efficient and natural to define `map` and `bind` in terms of `apply` and `join`, and this is how it's usually done
in this framework.

Finally, the last thing we want to do with the monads is to "stack up" multiple monads.
In particular, we want to take the individual monad for each layer, `ExpiringMonad`, `ErrorCatchingMonad`, and `ThinResourceMonad`,
and stack them up to get a single combined `ResourceMonad`.
Unfortunately, monads in general do not combine.
The core problem is that, when trying to define the combined monadic `join` for two monads `M` and `N`, we need a function
`M (N (M (N A))) -> M (N A)`. Each layer interferes with applying `join` to the other layer.
If we can define a way to "swap" the middle two layers, i.e. a function
`M (N (M (N A))) -> M (M (N (N A)))`, we could use `map` and `join` for each individual monad to simplify that to
`M (N A)`. The function which does this swap is called `distribute`.
It's not possible to write `distribute` in a general way, independent of which monads we're considering.
However, for any two particular monads we consider, it's usually simple in practice to write a `distribute` specialized
for those two monads.

#### The Resource Monad Stack

Notice how monads bridge two different layers of data, lifting operations on one layer to act on a higher layer.
In this framework, those monads can be described as bridging layers in the resource stack as follows:
```
        ┌────►Resource                                         
        │      ▲                                               
Resource│      │ThinResourceMonad                              
   Monad│      │                                               
        │  ┌─►ErrorCatching                                    
        │  │   ▲                                               
        │  │   │ErrorCatchingMonad                             
   Dynamics│   │                                               
      Monad│  Expiring                                         
        │  │   ▲                                               
        │  │   │ExpiringMonad                                  
        │  │   │                                               
        ├──┼──Dynamics     Discrete         Unstructured       
        ▲  ▲                ▲                ▲                 
       *│  │                │DiscreteMonad   │UnstructuredMonad
        │  │                │                │                 
        └──┴───────────────Value────────────Value              
```
Note that for `Discrete` and `Unstructured`, they also define `DiscreteDynamicsMonad`, `DiscreteResourceMonad`,
`UnstructuredDynamicsApplicative`, and `UnstructuredResourceApplicative`, which is what the `*` is meant to represent.
(Note: An applicative is a weaker form of monad, without the `bind` or `join` operators.)

#### Writing your own Monad

If you want to add another layer to the resource stack, you may consider writing your own monad.
For examples of this, look at the `DiscreteMonad` and its derived `DiscreteDynamicsMonad` and `DiscreteResourceMonad`,
or the more complicated `UnstructuredMonad` and its derived `UnstructuredDynamicsApplicative` and `UnstructuredResourceApplicative`.

In general, you should write the monad for the single layer you're trying to add first.
You can leverage the code generators referenced in those files to minimize the amount of manual effort by
auto-generating supplemental methods after you write `pure`, `apply`, and `join`.

Once you've done that, try to write `distribute` between your monad and the `DynamicsMonad`, and between your monad
and the `ResourceMonad`.
You can find formal mathematical rules it must follow online, but generally, it should be "natural" in the sense that it
doesn't introduce additional assumptions or information.
If it's possible to write `distribute`, you have a true composed monad, like `Discrete` does.
Copy and paste the definitions of `pure`, `apply`, and `join` from `DiscreteDynamicsMonad` and `DiscreteResourceMonad`,
modifying the types as needed (the logic and structure should remain the same, though).

If it's not possible to write `distribute`, you may only have an applicative. This was the case for `Unstructured`.
Copy and paste the definitions of `pure` and `apply`, and modify the types to fit (again, the logic and structure should
remain the same). This will still allow you to define `map`, which provides most of the benefits of a monad.

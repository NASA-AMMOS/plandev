# Integrating Data Rate

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https:#nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

Now is where the fun really begins! Although having the data rate in and out of our SSR is useful, we are often more
concerned with the total amount of volume we have in our SSR in order to make sure we don't over fill it and have
sufficient downlink opportunities to get all the data we collected back to Earth. In order to compute total volume, we
must figure out a way to integrate our `recording_rate`. It turns out there are many different methods in Aerie you can
choose to arrive at the total SSR volume, but each method has its own advantages and drawbacks. We will explore 4
different options for integration with the final option, a derived `Polynomial` resource, being our recommended
approach. As we progress through the options, you'll learn about a few more features of the resource framework that you
can use for different use cases in your model including the use of `Reactions` and **daemon** tasks.

## Method 1 - Increase volume within activity

The simplest method for performing an "integration" of `recording_rate` is to compute the integral directly within the
effect model of the activities who change the `recording_rate`. Before we do this, let's make sure we have a data volume
resource in our `DataModel` class. For each method, we are going to build a different data volume resource so we can
eventually compare them in the Aerie UI. As this is our simplest method, let's call this resource `ssr_volume_simple`
and make it store volume in Gigabits (Gb). Since we are going to directly effect this resource in our activities, this
will need to be a `cell`. The declaration looks just like `recording_rate`

as does the definition, initialization, and registration in the constructor:

```python
self.ssr_volume_simple = registrar.cell(0.0)
registrar.resource("ssr_volume_simple", self.ssr_volume_simple.get)
```

Taking a look at our `collect_data` activity, we can add the following line of code after the `await delay()` within its
body to compute the data volume resulting from the activity collecting data at a constant duration over the full
duration of the activity.

```python
model.data_model.ssr_volume_simple += rate * duration.to_number_in(Duration.SECONDS) / 1000.0
```

This line will increase `SRR_Volume_Simple` at the end of the activity by `rate` times `duration` divided by our magic
number to convert `Mb` to `Gb`. Note that the `duration` object has some helper functions like `to_number_in` to help you
convert the `duration` type to a `double` value.

There are a few notable issues with this approach. The first issue is that when a plan is simulated, the data volume of
the SSR will only increase at the very end of the activity even though in reality, the volume is growing linearly in
time throughout the duration of the activity. If your planners only need this level of fidelity to do their work, this
may be ok. However, if your planners need a little more fidelity during the time span of the activity, you could spread
out the data volume accumulation over many steps. That would look something like this in code,

```python
num_steps = 20
step_size = duration / num_steps
for i in range(num_steps):
    await delay(step_size)
model.data_model.ssr_volume_simple += this.rate * step_size.to_number_in(SECONDS) / 1000.0

```

which would replace the `delay()` and the single data volume increase line from above. The resulting timeline
for `ssr_volume_simple` would look like a stair step with the number of steps equal to `num_steps`. It's important to
remember we are still using a `Discrete` resource, so the resource is stored as a constant, "step-function" profile in
Aerie. We will show the use of a `Polynomial` resource in our final method to truly store and view data volume as a
linear profile.

Another issue with this approach is that iy does not transfer well to activities like `change_mag_mode` that alter
the `recording_rate` and do not return the rate back to its original value at the end of the activity (i.e. activities
whose effects on rate are not contained within the time span of the activity). In order to compute the magnetometer's
contribution to the data volume in `change_mag_mode`, we would need to multiply the `current_rate` by the duration since
the last mode change, or if no mode change has occurred, the beginning of the plan. While this is possible by using
a `Clock` resource to track time between mode changes, the `change_mag_mode` activity would now requires additional
context about the plan that would otherwise be unnecessary.

A third issue to note is that the computation of `recording_rate` and `ssr_volume_simple` are completely separate, and
both of them live within the activity effect model. In reality, these quantities are very much related and should be
tied together in some way. The relationship between rate and volume is activity independent, and thus it makes more
sense to define that relationship in our `DataModel` class instead of the activity itself.

Given these issues, we will hold off on implementing this approach for `change_mag_mode` and move forward to trying out
our next approach.

## Method 2 - Sample-based volume update

Another method to integration we can take is a numerical approach where we compute data volume by sampling the value of
the `recording_rate` at a fixed interval across the entire plan. In order to implement this method, we can `spawn()` a
simple task from our top-level `Mission` class that runs in the background while the plan is being simulated, which is
completely independent of activities in the plan. Such tasks are known as `daemon` tasks, and your mission model can
have an arbitrary number of them.

Before we create this task, let's add another discrete `cell` of type `double` called `ssr_volume_sampled` to
the `DataModel` class. Just as with other resources we have made, the declaration will look like

and the definition, initialization, and registration in the constructor will be

```python
self.ssr_volume_sampled = registrar.cell(0.0)
registrar.resource("ssr_volume_sampled", self.ssr_volume_sampled.get)
```

In addition to the resource, let's add another member variable to specify the sampling interval we'd like for our
integration. Choosing `60` seconds will result in the follow variable definition

```python
INTEGRATION_SAMPLE_INTERVAL = Duration.of(60, Duration.SECONDS)
```

Staying in the `DataModel` class, we can can create a member function called `integrate_sampled_ssr` that has no
parameters, which we will spawn from the `Mission` class shortly. For the sake of simplicity, we will define this
function to take the "right" Reimann Sum (a "rectangle" rule approximation) of the `recording_rate` over time. The
implementation of this function looks like this:

```python
@Task
def integrate_sampled_ssr(data_model: DataModel):
    while True:
        delay(INTEGRATION_SAMPLE_INTERVAL)
        current_recording_rate = data_model.recording_rate.get()
        data_model.ssr_volume_sampled += (
                current_recording_rate
                * INTEGRATION_SAMPLE_INTERVAL.to_number_in(Duration.SECONDS)
                / 1000.0)  # Mbit -> Gbit
```

As a programmer, you may be surprised to see an infinite `while` loop, but Aerie will shut down this task, effectively
breaking the loop, once the simulation reaches the end of the plan. Within the loop, the first thing we do is `delay()`
by our sampling interval and then retrieve the current value of `recording_rate`. Finally, we sum up our rectangle by
multiplying the current rate by the sampling interval. We could have easily chosen to use other numerical methods like
the "trapezoid" rule by storing the previous recording rate in addition to the current rate, but what we did is
sufficient for now.

The final piece we need to build into our model to get this method to work is a simple `spawn` with the `Mission` class
to our `integrate_sampled_ssr` method.

```python
spawn(self.data_model::integrate_sampled_ssr)
```

The issues with this approach to integration are probably fairly apparent to you. First of all, this approach is truly
an approximation, so the resulting volume may not be the actual volume if the sampled points don't align perfectly with
the changes in `recording_rate`. Secondly, the fact we are sampling at a fixed time interval means we could be computing
many more time points than we actually need if the recording rate isn't changing between time points. If you were to try
to scale up this approach, you might run into performance issues with your model where simulation takes much longer than
it needs to.

Despite these issues `daemon` tasks are a very effective tool in a modelers tool belt for describing "background"
behavior of your system. Examples for a spacecraft model could include the computation of geometry, battery degradation
over time, environmental effects, etc.

## Method 3 - Update volume upon change to rate

If you are looking for an efficient, yet accurate way to compute data volume from `recording_rate`, one method you could
take is to set up trigger that calls a function whenever `recording_rate` changes and then computes volume by
multiplying
the rate just before the latest change by the duration that has passed since the last change. Fortunately, there is a
fairly easy way to do this in Aerie's modeling framework.

Let's begin by creating one more `cell` called `ssr_volume_upon_rate_change` in our `DataModel` class (refer back to
previous instances in this tutorial for how to declare and define one of these). In addition to our volume resource, we
are also going to need a `Clock` resource to help us track the time between changes to `recording_rate`. Since this
resource is more of a "helper" resource and doesn't need to be exposed to our planners, we'll make it `private` and not
register it to the UI. Declaring and defining a `Clock` resource is not much different than declaring a `Discrete`
except you don't have to specify a primitive type.

```python
self.time_since_last_rate_change = registrar.cell(Duration.ZERO, behavior=Clock.behavior)
```

This will start a "stopwatch" right at the start of the plan so we can track the time between the start of the plan and
the first time `recording_rate` is changed. We'll also need one more member variable of type `Double`, which we'll
call `previous_rate` to keep track of the previous value of `recording_rate` for us.

Our next step is to build our trigger to react when there is a change to `recording_rate`. We can do this by leveraging
the `wheneverUpdates()` static method available within the framework's `Reactions` class

```python
spawn(monitor_recording_rate_updates(data_model))
```

:::{note}
The `Reactions` class has a couple more static methods that a modeler may find useful. The `every()` method allows you
to specify a duration to call a recurring action (we could have used this instead of our `spawn()` for the sampled
integration method). The `whenever()` method allows you to specify a `Condition`, which when met, would trigger an
action of your choosing. An example of a condition could be when a resource reaches a certain threshold.
:::

As you can see, this method takes a resource as its first argument and some `Runnable`, like a function call, as it's
second argument. We have specified that the function `upon_recording_rate_update` be called, so now we have to implement
that function within our `DataModel` class. The implementation of that function is below, which we will walk through
line by line.

```python
@Task
def monitor_recording_rate_updates(data_model):
    previous_recording_rate = 0.0
    for new_value in monitor_updates(lambda: data_model.recording_rate.get()):
        # Determine time elapsed since last update
        t = time_since_last_rate_change.get()
        # Update volume only if time has actually elapsed
        if !t.isZero():
            ssr_volume_upon_rate_change += previous_recording_rate * t.to_number_in(Duration.SECONDS) / 1000.0)  # Mbit -> Gbit
        
            previous_recording_rate = new_value
            # Restart clock (set back to zero)
            ClockEffects.restart(time_since_last_rate_change)
```

When the `recording_rate` resource changes, the first thing we do is determine how much time has passed since it last
changed (or since the beginning of the plan). If no time has passed, we don't want to re-integrate and double count
volume, but if time has passed, we do our simple integration by multiplying the previous rate by the elapsed time since
the value of rate changed. We then store the new value of rate as the previous rate and restart our stopwatch to we get
the right time next time the rate changes.

And that's it! Now, every time `recording_rate` changes, the SSR volume will update to the correct volume. However, the
volume is still a discrete resource, so volume will only change as a step function at time points where the rate
changes. Nonetheless, since `recording_rate` is piece-wise constant, you'll get the right answer for volume with no
error
at those time points.

## Method 4 - Derived volume from polynomial resource

We have finally arrived at the final method we'll go through for integrating `recording_rate`, and in some ways, this
one
is the most straightforward. We will define our data volume as polynomial resource, `ssr_volume_polynomial`, which we
can build by using an `integrate()` static method provided by the `PolynomialResources` class. As a polynomial resource,
we will actually see the volume increase linearly over time as opposed to in discrete chunks.
Since `ssr_volume_polynomial` will be derived directly from `recording_rate`, we can make this a `Resource` as opposed
to
a `cell`. The declaration of our new resource looks like this

while the definition and registration in the constructor of our `DataModel` class look like this

```java
self.ssr_volume_polynomial = scale(
    PolynomialResources.integrate(as_polynomial(this.recording_rate), 0.0), 1e-3) # Gbit
registrar.resource("ssr_volume_polynomial", ssr_volume_polynomial.approx_linear)
```

Breaking down the definition, we see the `integrate()` function takes the resource to integrate as the first argument,
but that argument requires the resource to be polynomial as well. Fortunately, there is a function
in the `PolynomialResources` module called `as_polynomial()` that can convert discrete resources like `recording_rate`
to
polynomial ones. The second argument is the initial value for the resource, which we have been assuming is `0.0` for
data volume. The `integrate()` function is then wrapped by `scale()`, another handy static method
in `PolynomialResources` to convert our resource from `Megabit` to `Gigabit`.

The resource registration is also slightly different than what we have seen thus far as we are using a `real()` method
as opposed to `discrete()` and we have to wrap our resource with yet another static helper method
in `PolynomialResources` called `assumeLinear()`. The reason we have to do this is that the UI currently does not have
support for `Polynomial` resources and can only render timelines as linear or constant segments. In our
case, `ssr_volume_polynomial` is actually linear anyway, so we are not "degrading" our resource by having to make this
down conversion.

Now in reality, our on-board `SSR` is going to have a max capacity, and if data is removed from the `SSR`, we want to
make sure our model stops decreasing the `SSR` volume once it reaches `0.0`. By good fortune, the Aerie framework
includes another static method in `PolynomialResources` called `clampedIntegral()` that allows you to build a resource
that takes care of all that messy logic to make sure you are adhering to your min/max limits.

If we wanted to build a "clamped" version of `ssr_volume_polynomial`, it would look something like this

```python
clamped_integrate = PolynomialResources.clamped_integrate(scale(
    as_polynomial(this.recording_rate), 1e-3),
    PolynomialResources.constant(0.0),
    PolynomialResources.constant(250.0),
    0.0)
ssr_volume_polynomial = clamped_integrate.integral()
```

The second and third arguments of `clamped_integrate()` are the min and max bounds for the integral and the final
argument is the starting value for the resource as it was in `integrate()`. The `clamped_integrate()` method actually
returns a `record` of three resources:

- integral – The clamped integral value (i.e. the main resource of interest)
- overflow – The rate of overflow when the integral hits its upper bound. You can integrate this to get cumulative
  overflow.
- underflow – The rate of underflow when the integral hits its lower bound. You can integrate this to get cumulative
  underflow.

As expected, the `integral()` resource is mapped to `ssr_volume_polynomial` to complete its definition.

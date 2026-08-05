# Using Current Value in an Effect Model

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

Now that we have our magnetometer resources, we need to build an activity that changes the `mag_data_mode` for us (
since `mag_data_rate` is a derived resource, we shouldn't have to touch it) and changes the overall SSR `recording_rate` to
reflect the magnetometer's data rate change. This activity, which we'll call `change_mag_mode`, only needs one parameter
of type `MagDataCollectionMode` to allow the user to request a change to the mode. Let's give that parameter a default
value of `LOW_RATE`.

In the effect model for this activity (which we'll call `run()` by convention), we can use the `set()` method in
the `DiscreteEffects` class to change the `mag_data_mode` to the value provided by our mode parameter. The computation of
the change to the `recording_rate` caused by the mode change is a little tricky because we need to know both the value of
the `mag_data_rate` before and after the mode change. Once we know those value, we can subtract the old value from the new
value to get the net increase to the `recording_rate`. If the new value happens to be less than the old value, our answer
will be negative, but that should be ok as long as we use the `increase()` method when effecting the `recording_rate`
resource.

We can get the current value of a resource with a static method called `currentValue()` available in the `Resources`
class. For our case here, we want to get the current value of the `mag_data_rate` **before** we actually change the mode
to the requested value, so we have to be a little careful about the order of operations within our effect model. The
resulting activity type and its effect model should look something like this:

:::{testsetup}
from pymerlin import MissionModel, simulate, Schedule, Duration
from pymerlin._internal._decorators import Validation, Task
from pymerlin._internal._registrar import CellRef
from pymerlin._internal._schedule import Directive
from pymerlin.model_actions import delay, spawn


@MissionModel
class Model:
    def __init__(self, registrar):
        self.counter: CellRef = registrar.cell(0)
        self.data_model = DataModel(registrar)
        registrar.resource("counter", self.counter.get)


@Model.ActivityType
def increment_counter(model):
    counter = model.counter
    counter.set_value(counter.get() + 1)


class DataModel:
    def __init__(self, registrar):
        self.recording_rate = registrar.cell(0)
        registrar.resource("recording_rate", self.recording_rate.get)

        self.ssr_volume_simple = registrar.cell(0.0)
        registrar.resource("ssr_volume_simple", self.ssr_volume_simple.get)

        self.ssr_volume_sampled = registrar.cell(0.0)
        registrar.resource("ssr_volume_sampled", self.ssr_volume_sampled.get)

        spawn(integrate_sampled_ssr(self))

        self.mag_data_mode = registrar.cell("OFF")
        registrar.resource("mag_data_mode", self.mag_data_mode.get)

        self.mag_data_rate = self.mag_data_mode.map(lambda mode: MagDataCollectionMode[mode])
        registrar.resource("mag_data_rate", self.mag_data_rate.get)

        self.total_data_rate = self.mag_data_rate + self.recording_rate
        registrar.resource("total_data_rate", self.total_data_rate.get)


INTEGRATION_SAMPLE_INTERVAL = Duration.of(60, Duration.SECONDS)


@Task
def integrate_sampled_ssr(data_model: DataModel):
    while True:
        delay(INTEGRATION_SAMPLE_INTERVAL)
        current_recording_rate = data_model.recording_rate.get()
        data_model.ssr_volume_sampled += (
                current_recording_rate
                * INTEGRATION_SAMPLE_INTERVAL.to_number_in(Duration.SECONDS)
                / 1000.0)  # Mbit -> Gbit


@Model.ActivityType
@Validation(lambda rate: rate < 100.0, "Collection rate is beyond buffer limit of 100.0 Mbps")
def collect_data(model, rate=0.0, duration="01:00:00"):
    duration = Duration.from_string(duration)
    model.data_model.recording_rate += rate
    delay(duration)
    model.data_model.ssr_volume_simple += rate * duration.to_number_in(Duration.SECONDS) / 1000.0
    model.data_model.recording_rate -= rate


MagDataCollectionMode = {
    "OFF": 0.0,  # kbps
    "LOW_RATE": 500.0,  # kbps
    "HIGH_RATE": 5000.0  # kbps
}
:::

:::{testcode}
@Model.ActivityType
def change_mag_mode(model, mode="LOW_RATE"):
    current_rate = model.data_model.mag_data_rate.get()
    new_rate = MagDataCollectionMode[mode]
    # Divide by 10^3 for kbps->Mbps conversion
    model.data_model.recording_rate += (new_rate-current_rate)/1.0e3
    model.data_model.mag_data_mode.set(mode)
:::

Looking at our new activity definition, you can see how we use the `increase()` effect on `recording_rate` to "increase"
the data rate based on the net data change from the old rate. You may also notice a magic number where we do a unit
conversion from `kbps` to `Mbps`, which isn't ideal. Later on in this tutorial, we will introduce a "Unit Aware"
resource framework that will help a bit with conversions like these if desired.

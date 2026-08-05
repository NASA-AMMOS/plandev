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


@Model.ActivityType
def change_mag_mode(model, mode="LOW_RATE"):
    current_rate = model.data_model.mag_data_rate.get()
    new_rate = MagDataCollectionMode[mode]
    # Divide by 10^3 for kbps->Mbps conversion
    model.data_model.recording_rate += (new_rate - current_rate) / 1.0e3
    model.data_model.mag_data_mode.set(mode)


MagDataCollectionMode = {
    "OFF": 0.0,  # kbps
    "LOW_RATE": 500.0,  # kbps
    "HIGH_RATE": 5000.0  # kbps
}


def main():
    profiles, spans, events = simulate(
        Model,
        Schedule.build(
            ("00:00:01", Directive("collect_data", {"rate": 20.0, "duration": "00:10:00"})),
            ("00:05:00", Directive("change_mag_mode", {"mode": "HIGH_RATE"})),
            ("00:07:00", Directive("change_mag_mode", {"mode": "LOW_RATE"})),
            ("00:09:00", Directive("change_mag_mode", {"mode": "OFF"})),
        ),
        # Schedule.build(("00:00:01", collect_data(rate=200.0, duration="00:00:01"))),
        "01:00:00"
    )

    print("spans")
    for span in spans:
        print("  ", span)

    for profile, segments in sorted(profiles.items()):
        print(profile)
        elapsed_time = Duration.ZERO
        for segment in segments:
            print("  ", elapsed_time, ":", segment.dynamics)
            elapsed_time += segment.extent


if __name__ == '__main__':
    main()

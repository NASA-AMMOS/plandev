from pymerlin import simulate, Schedule, Directive
from demo.model import Mission


def main():
    # Directive args are a dict of activity parameters; `{}` means "use the defaults".
    #
    # `downlink` is deliberately NOT scheduled here. It opens with
    # wait_until(data_volume_mb > 0.0), and the standalone framework runs activities
    # sequentially -- so by the time it starts, the preceding collect_data/compress_data
    # pair has already finished and drained the buffer back to 0. The condition can then
    # never become true, and _framework.py's wait_until polls forward 1us at a time
    # forever. That is a pre-existing standalone-framework limitation (it predates cell
    # evolution -- the same schedule hangs on an unmodified checkout), not something to
    # paper over in the demo. Run downlink against the Java-backed engine, which schedules
    # activities concurrently and satisfies the condition.
    schedule = Schedule.build(
        ("00:00:00", Directive("extend_solar_panels", {})),
        ("00:05:00", Directive("collect_data", {"data": 5012})),
        ("00:30:00", Directive("collect_data", {"data": 2048})),
        # Exercises cell evolution directly: emits heat once, then only observes as the
        # engine steps temperature_c toward ambient on its own.
        ("00:45:00", Directive("thermal_soak", {})),
        ("01:20:00", Directive("safe_mode", {})),
    )
    duration = "02:00:00"
    profiles, spans, events = simulate(Mission, schedule, duration)
    print("=== Profiles ===")
    for name, segments in profiles.items():
        print(f"  {name}: {segments}")
    print("\n=== Spans ===")
    for span in spans:
        print(f"  {span}")
    print("\n=== Events ===")
    for event in events:
        print(f"  {event}")


if __name__ == "__main__":
    main()

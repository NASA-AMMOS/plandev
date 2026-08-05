"""
Pure-Python discrete-event simulation driver for PyMerlin.

Drives a @MissionModel class directly — no Java, no GraalPy.
Returns the same (profiles, spans, events) tuple as the Java-backed simulate().

``dynamics='real'`` (real_evolution_roadmap.md) affects only the Java/GraalPy execution
path: when a packaged model runs inside a PlanDev worker, the Java shim registers a
``RealDynamics`` resource and computes a secant slope in ``getDynamics()``. This driver
does not replicate that — every ``ProfileSegment.dynamics`` field is a plain Python value
(the snapshot at the segment's left edge), regardless of the cell's ``dynamics`` setting.
This is intentional: the standalone engine is a lightweight logic checker, not a
profile-fidelity oracle. If you need to assert on slopes, run the JUnit suite against a
provisioned GraalPy host.
"""

import warnings
from collections import namedtuple
from queue import Queue

from pymerlin._internal import _globals
from pymerlin._internal._registrar import Registrar
from pymerlin._internal._schedule import Directive
from pymerlin._internal._task_status import Awaiting, Delayed
from pymerlin.duration import MICROSECONDS, Duration

ProfileSegment = namedtuple("ProfileSegment", "extent dynamics")
Span = namedtuple("Span", "type start duration")


# ---------------------------------------------------------------------------
# Reaction context installed into _globals so delay()/wait_until() yield
# ---------------------------------------------------------------------------

class _ReactionContext:
    def __init__(self, outbox: Queue, inbox: Queue):
        self._outbox = outbox
        self._inbox = inbox

    def yield_with(self, status):
        self._outbox.put(("yield", status))
        msg = self._inbox.get()
        if msg != "resume":
            raise RuntimeError(f"Unexpected sim message: {msg}")


# ---------------------------------------------------------------------------
# Discrete-event simulation engine
# ---------------------------------------------------------------------------

def simulate(model_class, schedule, duration):
    if type(duration) is str:
        duration = Duration.from_string(duration)
    duration_us = int(duration.to_number_in(MICROSECONDS))

    # --- initialise model ---
    registrar = Registrar()
    cell_values = {}

    model = model_class(registrar)

    evolving_cells = []  # (cell_ref, evolution_fn) for cells with evolution
    for cell_ref, initial_value, evolution in registrar.cells:
        cell_ref.id = id(cell_ref)
        cell_values[id(cell_ref)] = initial_value
        if evolution is not None:
            evolving_cells.append((cell_ref, evolution))

    _globals.cell_values_by_id = cell_values

    _globals._current_context[2] = model_class

    # --- profile / span tracking ---
    resource_names = [name for name, _ in registrar.resources]
    # profiles: resource_name -> list of (start_us, end_us, value)
    profile_segments_raw = {name: [] for name in resource_names}
    # snapshot values at time 0
    prev_values = {name: getter() for name, getter in registrar.resources}
    prev_snapshot_us = 0

    spans = []   # list of Span

    # --- pending activity queue: list of (start_us, activity_name, args, task_id) ---
    task_id_counter = [0]

    def _next_id():
        task_id_counter[0] += 1
        return task_id_counter[0]

    # Build initial queue from schedule
    pending = []  # (start_us, task_id, activity_name, args_dict, parent_id)
    for offset, directive in schedule.entries:
        if type(offset) is str:
            offset = Duration.from_string(offset)
        start_us = int(offset.to_number_in(MICROSECONDS))
        if isinstance(directive, Directive):
            name, args = directive.type, directive.args
        else:
            raise TypeError(f"Expected Directive, got {type(directive)}")
        pending.append((start_us, _next_id(), name, args, None))

    pending.sort(key=lambda x: x[0])

    def _apply_evolution(elapsed_us):
        """Step all evolving cells forward by elapsed_us microseconds."""
        if elapsed_us <= 0 or not evolving_cells:
            return
        elapsed = Duration.of(elapsed_us, MICROSECONDS)
        for cell_ref, evolution_fn in evolving_cells:
            current = cell_values[cell_ref.id]
            cell_values[cell_ref.id] = evolution_fn(current, elapsed)

    def _snapshot_profiles(at_us):
        """Close the current segment at at_us using whatever value was current since prev_snapshot_us."""
        nonlocal prev_snapshot_us
        if at_us <= prev_snapshot_us:
            # Refresh prev_values in place so post-emit values are picked up
            for res_name, getter in registrar.resources:
                prev_values[res_name] = getter()
            return
        # Step evolving cells forward before closing the segment.
        _apply_evolution(at_us - prev_snapshot_us)
        for res_name, getter in registrar.resources:
            profile_segments_raw[res_name].append(
                (prev_snapshot_us, at_us, prev_values[res_name])
            )
            prev_values[res_name] = getter()
        prev_snapshot_us = at_us

    def _run_activity(activity_name, args, start_us):
        """Run one activity to completion, handling delay/spawn/wait_until."""
        if activity_name not in model_class.activity_types:
            raise ValueError(f"Unknown activity type: {activity_name!r}")

        task_def = model_class.activity_types[activity_name]
        raw_func = getattr(task_def, "raw_func", None)
        if raw_func is None:
            raise ValueError(f"Activity {activity_name!r} has no raw_func")

        inbox:  Queue = Queue(maxsize=1)
        outbox: Queue = Queue(maxsize=1)
        ctx = _ReactionContext(outbox, inbox)

        spawn_queue: Queue = Queue()

        def _spawner(task_instance):
            child_name = getattr(task_instance, "activity_name", None)
            spawn_queue.put((child_name, {}))

        import threading

        def _run():
            _globals.reaction_context = ctx
            _globals._current_context[1] = _spawner
            try:
                raw_func(model, **args)
                outbox.put(("done",))
            except Exception as exc:
                outbox.put(("error", exc))

        thread = threading.Thread(target=_run, daemon=True)
        thread.start()

        current_us = start_us
        finish_us = start_us

        while True:
            msg = outbox.get()

            # Drain spawns into pending before processing yield
            while not spawn_queue.empty():
                child_name, child_args = spawn_queue.get_nowait()
                if child_name:
                    pending.append((current_us, _next_id(), child_name, child_args, None))
                    pending.sort(key=lambda x: x[0])

            if msg[0] == "done":
                finish_us = current_us
                break

            if msg[0] == "error":
                raise msg[1]

            # ("yield", status)
            status = msg[1]

            if isinstance(status, Delayed):
                step_us = int(status.duration.to_number_in(MICROSECONDS))
                current_us += step_us
                _snapshot_profiles(current_us)
                finish_us = current_us
                inbox.put("resume")

            elif isinstance(status, Awaiting):
                condition = status.condition
                # Poll condition: advance 1µs at a time until true
                while not condition():
                    current_us += 1
                    _snapshot_profiles(current_us)
                finish_us = current_us
                inbox.put("resume")

            else:
                # Unknown status — resume immediately
                inbox.put("resume")

        return finish_us

    # --- main simulation loop ---
    while pending:
        start_us, _task_id, act_name, args, _parent_id = pending.pop(0)

        if start_us > duration_us:
            break

        _snapshot_profiles(start_us)

        try:
            finish_us = _run_activity(act_name, args, start_us)
        except Exception as e:
            warnings.warn(f"Activity {act_name!r} raised: {e}")
            finish_us = start_us

        finish_us = min(finish_us, duration_us)
        _snapshot_profiles(finish_us)

        spans.append(Span(
            type=act_name,
            start=Duration.of(start_us, MICROSECONDS),
            duration=Duration.of(finish_us - start_us, MICROSECONDS),
        ))

    # Final profile snapshot at duration boundary
    _snapshot_profiles(duration_us)

    # --- pack profiles ---
    profiles = {}
    for res_name in resource_names:
        segs = []
        for seg_start, seg_end, val in profile_segments_raw[res_name]:
            extent = Duration.of(seg_end - seg_start, MICROSECONDS)
            segs.append(ProfileSegment(extent=extent, dynamics=val))
        if segs:
            profiles[res_name] = segs

    _globals.cell_values_by_id.clear()

    return profiles, spans, []
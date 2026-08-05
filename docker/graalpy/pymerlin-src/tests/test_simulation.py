import pytest
from py4j.java_gateway import Py4JJavaError

from pymerlin import MissionModel, checkout
from pymerlin import Schedule
from pymerlin import simulate, Span
from pymerlin._internal._decorators import Validation, ValidationResult, Task
from pymerlin._internal._framework import ProfileSegment
from pymerlin._internal._registrar import Registrar
from pymerlin._internal._schedule import Directive
from pymerlin.clock import clock
from pymerlin.duration import Duration, SECONDS
from pymerlin.model_actions import delay, spawn, call, wait_until
from pymerlin.reactions import monitor_updates


@MissionModel
class TestMissionModel:
    def __init__(self, registrar: Registrar):
        self.list = registrar.cell([])
        self.counter = registrar.cell(0)
        self.linear = registrar.cell((0, 1), evolution=linear_evolution)
        self.clock = clock(registrar)

        registrar.resource("counter", self.counter.get)


def linear_evolution(x, d):
    initial = x[0]
    rate = x[1]
    delta = rate * d.to_number_in(SECONDS)
    return initial + delta, rate


@TestMissionModel.ActivityType
def noop(mission):
    pass


@TestMissionModel.ActivityType
def add_to_list(mission, item):
    mission.counter.set(mission.counter.get() + 1)
    mission.list.emit(mission.list.get() + [item])


@TestMissionModel.ActivityType
def delay_one_hour(mission):
    mission.counter.set(mission.counter.get() + 1)
    delay("01:00:00")


@TestMissionModel.ActivityType
def clear_list(mission, item):
    mission.counter.set(mission.counter.get() + 1)
    mission.list.emit([])


def test_checkout():
    checkout()


def test_empty():
    """
    Empty schedule should result in no spans
    """
    profiles, spans, events = simulate(TestMissionModel, Schedule.empty(), "24:00:00")
    assert 0 == len(spans)


def test_noop():
    """
    Schedule with noop should have a single span, and all profiles should have exactly one segment
    """
    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:01", Directive("noop", {}))),
                                       "24:00:00")
    assert spans == [Span("noop", Duration.from_string("00:00:01"), Duration.ZERO)]


def test_effect():
    """
    Make sure the counter gets incremented when expected by observing it from another activity
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        delay("00:00:00")
        assert mission.counter.get() == 0
        delay("00:05:00")
        assert mission.counter.get() == 1
        return

    simulate(TestMissionModel,
             Schedule.build(("00:00:00", Directive("activity", {})), ("00:00:01", Directive("delay_one_hour", {}))),
             "24:00:00")


def test_exception():
    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        delay("00:00:00")
        raise Exception("Exception in task!")

    with pytest.raises(Py4JJavaError) as e:
        simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))), "24:00:00")
        # TODO make sure ample information is extracted from the java exception


@pytest.mark.skip("This test hangs, but it would be nice if it raised an exception instead")
def test_forgot_to_await():
    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        delay("00:00:01")
        delay("00:00:01")

    with pytest.raises(Py4JJavaError) as e:
        simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))), "24:00:00")
        # TODO make sure ample information is extracted from the java exception


def test_spawn():
    """
    Check that spawning a child activity creates the correct spans, and that concurrent events become visible after yield
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        spawn(other_activity(mission))
        mission.counter.set(345)
        assert mission.counter.get() == 345

    @TestMissionModel.ActivityType
    def other_activity(mission: TestMissionModel):
        assert mission.counter.get() == 123
        delay("00:00:01")
        assert mission.counter.get() == 345

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))),
                                       "24:00:00")
    assert spans == [Span("activity", Duration.ZERO, Duration.SECOND),
                     Span("other_activity", Duration.ZERO, Duration.SECOND)]


def test_spawn_task():
    """
    Check that spawning a child task creates no new spans, and that concurrent events become visible after yield
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        spawn(subtask(mission))
        mission.counter.set(345)
        assert mission.counter.get() == 345
        delay("00:00:05")
        assert mission.counter.get() == 678

    @Task
    def subtask(mission):
        assert mission.counter.get() == 123
        delay("00:00:01")
        assert mission.counter.get() == 345
        mission.counter.set(678)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))),
                                       "24:00:00")
    assert spans == [Span("activity", Duration.ZERO, Duration.of(5, SECONDS)), ]


def test_call():
    """
    Check that calling a child activity creates the correct spans, and that the parent resumes after the child finishes
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        call(other_activity(mission))
        assert mission.counter.get() == 345
        delay("00:00:01")

    @TestMissionModel.ActivityType
    def other_activity(mission: TestMissionModel):
        assert mission.counter.get() == 123
        delay("00:00:01")
        assert mission.counter.get() == 123
        mission.counter.set(345)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))),
                                       "24:00:00")
    assert spans == [Span("activity", Duration.ZERO, Duration.of(2, SECONDS)),
                     Span("other_activity", Duration.ZERO, Duration.SECOND)]


def test_call_task():
    """
    Check that calling a child task creates no new spans, and that the parent resumes after the child finishes
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        call(subtask(mission))
        assert mission.counter.get() == 345
        delay("00:00:01")

    @Task
    def subtask(mission: TestMissionModel):
        assert mission.counter.get() == 123
        delay("00:00:01")
        assert mission.counter.get() == 123
        mission.counter.set(345)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))),
                                       "24:00:00")
    assert spans == [Span("activity", Duration.ZERO, Duration.of(2, SECONDS)), ]


def test_discrete_condition():
    """
    Check that a task is resumed correctly when a condition becomes true
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        wait_until(lambda: mission.counter.get() == 345)
        assert mission.counter.get() == 345

    @TestMissionModel.ActivityType
    def other_activity(mission: TestMissionModel):
        mission.counter.set(345)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {})),
                                                                        ("00:00:15",
                                                                         Directive("other_activity", dict()))),
                                       "24:00:00")

    assert spans == [Span("activity", Duration.ZERO, Duration.of(15, SECONDS)),
                     Span("other_activity", Duration.of(15, SECONDS), Duration.ZERO)]


def test_concurrent_effects():
    """
    Make sure the counter gets incremented when expected by observing it from another activity
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.emit(lambda x: x + 1)
        assert mission.counter.get() == 1
        delay("00:00:00")
        assert mission.counter.get() == 2

    simulate(TestMissionModel,
             Schedule.build(("00:00:00", Directive("activity", {})), ("00:00:00", Directive("activity", {}))),
             "24:00:00")


def test_autonomous_condition():
    """
    Check that a task is resumed correctly when a condition becomes true
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        assert mission.linear.get()[0] == 0
        delay("00:00:01")
        assert mission.linear.get()[0] == 1
        delay("00:00:02")
        assert mission.linear.get()[0] == 3

    simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))), "24:00:00")


@pytest.mark.skip("Support for non-keyword args not yet implemented")
def test_activity_args():
    """
    Check that an activity receives args in the correct order
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel, number1, number2):
        assert number1 == 123
        assert number2 == 123

    simulate(TestMissionModel, Schedule.build(("00:00:00", activity(123, 345))), "24:00:00")


def test_activity_kwargs():
    """
    Check that an activity receives keyword args
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel, number1, number2):
        assert number1 == 123
        assert number2 == 345

    simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", dict(number2=345, number1=123)))),
             "24:00:00")


@pytest.mark.skip()
def test_validation():
    @TestMissionModel.ActivityType
    @Validation(lambda count: count > 0, "count must be positive")
    @Validation(lambda axis: axis in ["X", "Y", "Z"], "axis must be X, Y, or Z")
    @Validation(lambda count, axis: count > 10 if axis == "X" else True, "count must be at least 10 if axis is X")
    def activity(mission: TestMissionModel, count, axis):
        pass

    assert activity(count=-1, axis="Z").validate() == [
        ValidationResult(False, "count must be positive"),
        ValidationResult(True, "axis must be X, Y, or Z"),
        ValidationResult(True, "count must be at least 10 if axis is X")
    ]

    assert activity(count=5, axis="X").validate() == [
        ValidationResult(True, "count must be positive"),
        ValidationResult(True, "axis must be X, Y, or Z"),
        ValidationResult(False, "count must be at least 10 if axis is X")
    ]

    assert activity(count=5, axis="Y").validate() == [
        ValidationResult(True, "count must be positive"),
        ValidationResult(True, "axis must be X, Y, or Z"),
        ValidationResult(True, "count must be at least 10 if axis is X")
    ]

    assert activity(count=11, axis="X").validate() == [
        ValidationResult(True, "count must be positive"),
        ValidationResult(True, "axis must be X, Y, or Z"),
        ValidationResult(True, "count must be at least 10 if axis is X")
    ]

    assert activity(count=5, axis=3.14).validate() == [
        ValidationResult(True, "count must be positive"),
        ValidationResult(False, "axis must be X, Y, or Z"),
        ValidationResult(True, "count must be at least 10 if axis is X")
    ]


def test_whenever_updates_condition():
    """
    Check that a task is resumed correctly each time a cell is updated
    """

    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        mission.counter.set(123)
        expected_series = iter((345, 678, 91011))
        for new_value in monitor_updates(lambda: mission.counter.get()):
            print(new_value)
            assert new_value == next(expected_series)
        raise Exception("This should be unreachable")

    @TestMissionModel.ActivityType
    def other_activity(mission: TestMissionModel):
        mission.counter.set(345)
        delay("00:00:07")
        mission.counter.set(678)
        delay("00:00:09")
        mission.counter.set(91011)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {})),
                                                                        ("00:00:15",
                                                                         Directive("other_activity", dict()))),
                                       "24:00:00")

    assert spans == [
        Span("other_activity", Duration.of(15, SECONDS), Duration.of(16, SECONDS)),
        Span("activity", Duration.ZERO, None)  # Unfinished]
    ]

    assert profiles["counter"] == [
        ProfileSegment(extent=Duration.from_string("+00:00:15.000000"), dynamics=123.0),
        ProfileSegment(extent=Duration.from_string("+00:00:07.000000"), dynamics=345.0),
        ProfileSegment(extent=Duration.from_string("+00:00:09.000000"), dynamics=678.0),
        ProfileSegment(extent=Duration.from_string("+23:59:29.000000"), dynamics=91011.0)
    ]


def test_clock():
    @TestMissionModel.ActivityType
    def activity(mission: TestMissionModel):
        clock = mission.clock.start()
        assert clock.get() == Duration.ZERO
        delay("00:00:09")
        assert clock.get() == Duration.of(9, SECONDS)
        clock.reset()
        assert clock.get() == Duration.ZERO
        delay("12:34:56")
        assert clock.get() == Duration.from_string("12:34:56")

    simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("activity", {}))), "24:00:00")


def test_registrar_warning():
    @MissionModel
    class Squirrel:
        def __init__(self, registrar):
            self.registrar = registrar  # Squirreling away the registrar

    with pytest.warns(
            match="Saving registrar in a field or local variable is not recommended - it only works during model initialization"):
        simulate(Squirrel, Schedule.empty(), "24:00:00")


def constant_slope(rate):
    def evolution(initial, d):
        delta = rate * d.to_number_in(SECONDS)
        return initial + delta

    return evolution


def test_derivation():
    @MissionModel
    class TestModel:
        def __init__(self, registrar):
            self.base = registrar.cell(0, evolution=constant_slope(1))
            self.scaled = self.base * 12
            self.quadratic = self.base ** 2
            self.binned = self.base - (self.base % 10)
            self.zero = self.base - self.base

            registrar.resource("base", self.base.get)
            registrar.resource("quadratic", self.quadratic.get)
            registrar.resource("binned", self.binned.get)

            spawn(sampler(self))

    @Task
    def sampler(model):
        while True:
            delay("00:11:01")
            model.base.emit(lambda x: x)  # force a sample
            assert model.scaled.get() == model.base.get() * 12
            assert model.quadratic.get() == model.base.get() ** 2
            assert model.binned.get() == model.base.get() - (model.base.get() % 10)
            assert model.zero.get() == 0

    profiles, spans, events = simulate(TestModel, Schedule.empty(), "01:00:00")
    assert profiles == {
        'base': [ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=0.0),
                 ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=661.0),
                 ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=1322.0),
                 ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=1983.0),
                 ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=2644.0),
                 ProfileSegment(extent=Duration("+00:04:55.000000"), dynamics=3305.0)],
        'binned': [ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=0.0),
                   ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=660.0),
                   ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=1320.0),
                   ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=1980.0),
                   ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=2640.0),
                   ProfileSegment(extent=Duration("+00:04:55.000000"), dynamics=3300.0)],
        'quadratic': [ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=0.0),
                      ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=436921.0),
                      ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=1747684.0),
                      ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=3932289.0),
                      ProfileSegment(extent=Duration("+00:11:01.000000"), dynamics=6990736.0),
                      ProfileSegment(extent=Duration("+00:04:55.000000"), dynamics=10923025.0)]}

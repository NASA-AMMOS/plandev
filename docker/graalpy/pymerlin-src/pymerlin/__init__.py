"""
This is the pymerlin module, doing pymerlin things
"""

from ._internal._decorators import MissionModel, MissionModelBase
from ._internal._framework import simulate, Span, ProfileSegment
from ._internal._registrar import Registrar
from ._internal._schedule import Schedule, Directive
from .duration import Duration


def checkout():
    """
    Check that your pymerlin installation was successful
    """
    # Exercise enough of the system to flush out any configuration issues, and ideally provide user with actionable
    # feedback in the case of misconfiguration

    success = True  # If this is False at the bottom of the function, checkout failed

    @MissionModel
    class TestMissionModel:
        def __init__(self, registrar: Registrar):
            self.counter = registrar.cell(0)
            registrar.resource("counter", self.counter.get)

    @TestMissionModel.ActivityType
    def test_activity(mission: TestMissionModel):
        mission.counter.emit(lambda x: x + 1)

    profiles, spans, events = simulate(TestMissionModel, Schedule.build(("00:00:00", Directive("test_activity", {}))), "00:00:01")
    if profiles != {'counter': [ProfileSegment(extent=Duration.from_string("+00:00:01.000000"), dynamics=1.0)]}:
        print("Profiles didn't check out: ", profiles)
        success = False
    if spans != [Span(type='test_activity', start=Duration.from_string("+00:00:00.000000"), duration=Duration.from_string("+00:00:00.000000"))]:
        print("Spans didn't check out: ", profiles)
        success = False

    if success:
        print("pymerlin checkout successful: All systems GO 🚀")
    else:
        print("Checkout detected issues ⚠️")


__all__ = [
    MissionModel,
    MissionModelBase,
    simulate,
    Span,
    ProfileSegment,
    Registrar,
    Schedule,
    Directive,
    Duration,
    checkout
]
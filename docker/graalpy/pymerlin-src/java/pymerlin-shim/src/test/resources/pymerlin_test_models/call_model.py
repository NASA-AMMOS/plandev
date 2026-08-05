"""
Test-only model exercising call() — the Phase 3 (§6) exit-criterion action the demo model
does not use. parent_act call()s child_act, which delays 3 minutes; call() must BLOCK the
parent until the child finishes, so parent_act's own span should be 3 minutes long and its
final emit should land at t+3min, not t+0.
"""

from pymerlin import MissionModel
from pymerlin.model_actions import delay, call


@MissionModel
class CallMission:
    def __init__(self, registrar):
        self.stage = registrar.cell("START")
        self.x = registrar.cell(0)

        registrar.resource("/stage", self.stage)
        registrar.resource("/x", self.x)


@CallMission.ActivityType
def parent_act(mission):
    mission.stage.emit("PARENT_BEFORE")
    call(child_act(mission))          # blocks until child_act completes (3 min from now)
    mission.stage.emit("PARENT_AFTER")


@CallMission.ActivityType
def child_act(mission):
    mission.stage.emit("CHILD")
    mission.x.emit(lambda v: v + 1)
    delay("00:03:00")

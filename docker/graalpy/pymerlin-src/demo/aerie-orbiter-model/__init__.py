"""
Aerie Orbiter mission model, ported to pymerlin.

A faithful Python port of the NASA-AMMOS ``aerie-orbiter-model`` Java mission
model.  The subsystems (geometry/SPICE, power, data, telecom, radar) mirror the
original ``missionmodel`` package; ``mission.Mission`` is the top-level
``@MissionModel`` and defines all activity types.

Note on engines: these files target the pymerlin *shim* protocol (Java/Merlin
engine), where daemons, continuous resources and ``spawn(child(args))`` behave
as in the original model.  They also run on pymerlin's pure-Python engine,
which executes one activity to completion at a time and does not propagate
``spawn`` arguments; use the ``run_orbiter_demo`` scenario for a coherent
single-thread demonstration there.
"""

from .mission import Mission
from .configuration import Configuration

__all__ = ["Mission", "Configuration"]

"""
This module provides actions that tasks can take. Certain actions are labeled `async`, which means they must be called
with the `await` keyword - for example, `await delay("01:00:00")`
"""

import pymerlin._internal._task_status
import pymerlin.duration
import pymerlin._internal._globals
from pymerlin._internal._task_specification import TaskInstance


def delay(duration):
    if type(duration) is str:
        duration = pymerlin.duration.Duration.from_string(duration)
    elif type(duration) is not pymerlin.duration.Duration:
        raise Exception("Argument to delay must be a Duration or a string representing a duration")
    return _yield_with(pymerlin._internal._task_status.Delayed(duration))


def spawn(child):
    """
    :param coro:
    :return:
    """
    pymerlin._internal._globals._current_context[1](child)


def call(child):
    if type(child) is not TaskInstance:
        raise ValueError("Should be TaskDefinition, was: " + repr(child))
    return _yield_with(pymerlin._internal._task_status.Calling(child))


def wait_until(condition):
    """
    :param condition: A function returning True or False
    """
    return _yield_with(pymerlin._internal._task_status.Awaiting(condition))


def _yield_with(status):
    return pymerlin._internal._globals.reaction_context.yield_with(status)

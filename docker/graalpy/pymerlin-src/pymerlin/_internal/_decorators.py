"""
Provide the MissionModel decorator, which generates the .ActivityType decorators on the decorated class.
"""

import inspect
import warnings
from typing import Any, Callable, ClassVar, Union

from pymerlin._internal._task_specification import TaskInstance


def MissionModel(cls):
    """
    Decorate a class
    :param cls:
    :return:
    """
    if not inspect.isclass(cls):
        warnings.warn("@MissionModel decorator is intended to be used on classes")

    cls.activity_types = {}

    def ActivityType(func):
        if type(func) == TaskDefinition:
            activity_definition = func
        elif callable(func):
            activity_definition = TaskDefinition(func.__name__, func)
            activity_definition.raw_func = func
        else:
            raise ValueError("Cannot decorate " + repr(func) + " with @ActivityType")
        if activity_definition.name in cls.activity_types:
            warnings.warn("Re-defining activity type: " + activity_definition.name)
        cls.activity_types[activity_definition.name] = activity_definition
        return activity_definition
    cls.ActivityType = ActivityType
    return cls


class TaskDefinition:
    """
    TaskDefinition can produce a TaskInstance given all of the arguments for that task.
    """
    def __init__(self, name, func):
        self.name = name
        self.inner = func
        self.raw_func = func

    def __call__(self, *args, **kwargs):
        return self.make_instance(*args, **kwargs)

    def make_instance(self, *args, **kwargs) -> TaskInstance:
        instance = TaskInstance(lambda: self.inner.__call__(*args, **kwargs))
        instance.activity_name = self.name
        # Store the call arguments so spawn()/call() can forward them to the child
        # activity. Previously only `activity_name` was attached and the args stayed
        # trapped in the lambda closure, so `spawn(collect_data(mission, data=512))`
        # silently reached the child as `data=1024` (its default). args[0] is the
        # mission/model instance and is re-injected by the runner, not forwarded.
        instance.args = args
        instance.kwargs = kwargs
        return instance


class MissionModelBase:
    """
    Optional base class for @MissionModel classes, purely for the benefit of type
    checkers and IDEs.

    @MissionModel attaches ``activity_types`` and ``ActivityType`` to the class at
    runtime, inside a function body. Type checkers don't execute function bodies, so
    they never see those assignments and report ``@Mission.ActivityType`` as an access
    to an unknown attribute. Declaring the two members here, and inheriting from this
    class, tells the checker what the decorator is about to add.

    Inheriting is optional and changes nothing at runtime: @MissionModel overwrites
    both members on the decorated class either way. Models that don't subclass this
    still work exactly as before.

        @MissionModel
        class Mission(MissionModelBase):
            ...
    """

    activity_types: ClassVar[dict]

    @staticmethod
    def ActivityType(func: Union[Callable[..., Any], "TaskDefinition"]) -> "TaskDefinition":
        """Replaced by @MissionModel with a closure bound to the decorated class."""
        raise NotImplementedError(
            "ActivityType is installed by @MissionModel; decorate the class with it"
        )
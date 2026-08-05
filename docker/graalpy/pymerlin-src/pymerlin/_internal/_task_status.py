from collections import namedtuple

Completed = namedtuple("Completed", "value")
Delayed = namedtuple("Delayed", "duration")
Calling = namedtuple("Calling", "child")
Awaiting = namedtuple("Awaiting", "condition")

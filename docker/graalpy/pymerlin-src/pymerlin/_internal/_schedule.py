from collections import namedtuple

from pymerlin.duration import Duration


class Schedule:
    def __init__(self):
        self.entries = []

    @staticmethod
    def build(*entries):
        res = Schedule()
        for time, directive in entries:
            if type(time) is str:
                time = Duration.from_string(time)
            res.entries.append((time, directive))
        return res

    @staticmethod
    def empty():
        return Schedule.build()

Directive = namedtuple("Directive", "type args")
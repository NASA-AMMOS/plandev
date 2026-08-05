from pymerlin.duration import ZERO


def clock(registrar):
    """
    Returns an autonomously evolving cell that can track time
    """
    return ClockMaker(registrar)


class ClockMaker:
    def __init__(self, registrar):
        self._system_clock = registrar.cell(ZERO, evolution=lambda x, d: x + d)

    def start(self):
        return DerivedClock(self._system_clock, -self._system_clock.get())


class DerivedClock:
    def __init__(self, reference, offset):
        self.reference = reference
        self.offset = offset

    def get(self):
        return self.reference.get() + self.offset

    def reset(self):
        self.offset = -self.reference.get()

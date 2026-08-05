"""
Data storage model.

Port of ``missionmodel.data.Data`` and ``missionmodel.data.Bucket``.

The Java model expresses data flow with continuous *polynomial* resources whose
rates are reconciled by a ``LinearBoundaryConsistencySolver`` to honour the
onboard storage cap across prioritised bins.  pymerlin's pure-Python engine has
no continuous/polynomial resources, so this is a faithful *discrete* adaptation:
volumes and cumulative totals are integer/float cells advanced each timestep by
:meth:`Data.integrate` (invoked from the mission's physics daemon).  The
behaviour it reproduces:

* prioritised onboard bins (lower index = higher priority) sharing one cap,
* separate ground bins tracking how much of each onboard bin has been downlinked,
* generation (receive), deletion (remove), and playback (downlink) flows.

All volumes are in bits and all rates in bits/second.
"""

MAX_BOUND = float("inf")


class Bucket:
    def __init__(self, registrar, name, volume_ub=MAX_BOUND):
        self.name = name
        self.volume = registrar.cell(0.0)                 # current stored volume (bits)
        self.received = registrar.cell(0.0)               # cumulative received (bits)
        self.removed = registrar.cell(0.0)                # cumulative removed (bits)
        self.desired_receive_rate = registrar.cell(0.0)   # bps
        self.desired_remove_rate = registrar.cell(0.0)    # bps
        self._volume_ub = volume_ub                       # constant or getter

    @property
    def volume_ub(self) -> float:
        return self._volume_ub() if callable(self._volume_ub) else self._volume_ub

    def register_states(self, registrar):
        n = self.name
        registrar.resource(n + ".volume", self.volume)
        registrar.resource(n + ".receivedVolume", self.received)
        registrar.resource(n + ".removedVolume", self.removed)
        registrar.resource(n + ".desiredReceiveRate", self.desired_receive_rate)
        registrar.resource(n + ".desiredRemoveRate", self.desired_remove_rate)


class Data:
    def __init__(self, registrar, num_buckets, max_volume_getter, data_rate_getter):
        """
        :param num_buckets: number of prioritised onboard bins (and matching ground bins)
        :param max_volume_getter: getter -> onboard storage cap (bits)
        :param data_rate_getter: getter -> playback/downlink data rate (bps)
        """
        self._registrar = registrar
        self.max_volume = max_volume_getter
        self.data_rate = data_rate_getter

        self.onboard_buckets = [Bucket(registrar, f"scBin{i}") for i in range(num_buckets)]
        self.ground_buckets = [Bucket(registrar, f"gndBin{i}") for i in range(num_buckets)]

        # Playback request state, set by the PlaybackData activity.
        self.downlink_active = registrar.cell(False)
        self.volume_requested = registrar.cell(0.0)   # bits remaining to downlink (0 => no volume goal)

    def get_onboard_bin(self, i: int) -> Bucket:
        return self.onboard_buckets[i]

    def get_ground_bin(self, i: int) -> Bucket:
        return self.ground_buckets[i]

    def onboard_volume(self) -> float:
        return sum(b.volume.get() for b in self.onboard_buckets)

    def ground_received(self) -> float:
        return sum(b.received.get() for b in self.ground_buckets)

    # --- Discrete integration step (called by the physics daemon) --------------
    def integrate(self, dt_seconds: float):
        cap = self.max_volume()

        # 1) Generation / deletion into each onboard bin, capped in priority order.
        used = 0.0
        for b in self.onboard_buckets:
            net_rate = b.desired_receive_rate.get() - b.desired_remove_rate.get()
            new_vol = b.volume.get() + net_rate * dt_seconds
            # Per-bin upper bound: bin's own bound intersected with remaining cap.
            remaining_cap = max(0.0, cap - used)
            upper = min(b.volume_ub, remaining_cap)
            new_vol = max(0.0, min(new_vol, upper))
            stored = new_vol - b.volume.get()
            if stored > 0:
                b.received.set(b.received.get() + stored)
            elif stored < 0:
                b.removed.set(b.removed.get() - stored)
            b.volume.set(new_vol)
            used += new_vol

        # 2) Playback / downlink, priority order, limited by the playback rate.
        if self.downlink_active.get():
            bits_left = self.data_rate() * dt_seconds
            vol_goal = self.volume_requested.get()
            if vol_goal > 0:
                bits_left = min(bits_left, vol_goal)
            total_moved = 0.0
            for sc, gnd in zip(self.onboard_buckets, self.ground_buckets):
                if bits_left <= 0:
                    break
                # Can only send data that is on board AND not already downlinked.
                available = min(sc.received.get() - gnd.received.get(), sc.volume.get())
                move = max(0.0, min(bits_left, available))
                if move > 0:
                    gnd.received.set(gnd.received.get() + move)
                    bits_left -= move
                    total_moved += move
            if vol_goal > 0:
                self.volume_requested.set(max(0.0, vol_goal - total_moved))

    def register_states(self, registrar):
        for b in self.onboard_buckets:
            b.register_states(registrar)
        for b in self.ground_buckets:
            b.register_states(registrar)
        registrar.resource("onboard.volume", self.onboard_volume)
        registrar.resource("ground.receivedVolume", self.ground_received)
        registrar.resource("playbackDataRate", lambda: self.data_rate())
        registrar.resource("volumeRequestedToDownlink", self.volume_requested)

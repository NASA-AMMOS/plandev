# Tuning Fidelity

pymerlin (and Aerie's) strength is its ability to compose several subsystem models and observe behavior of the
integrated system. It is not necessarily the best tool for detailed modeling of individual subsystems.

Different mission phases have different needs for simulation:
- Mission planning requires simulation to run quickly across large time horizons, and to provide enough fidelity to
  perform trade-offs between different parameters. It may be desirable to automatically run many simulations with small
  variations in parameters, sometimes called "Monte-Carlo" simulations
- Operations requires simulation to catch command errors quickly and reliably, but the simulated time spans can be
  relatively short (typically days or weeks depending on the operations concept)

There are also differences in how activity plans are generated in different mission phases. During mission planning, a
notional schedule that merely approximates the duty cycles of communication, engineering, and science activities may be
sufficient. During operations, much more precision is required of the activity plan, and additional tools and processes
such as external schedulers and strict configuration management of plans come into play.
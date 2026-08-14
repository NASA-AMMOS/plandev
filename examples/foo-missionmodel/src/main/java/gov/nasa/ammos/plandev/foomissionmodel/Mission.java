package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.contrib.models.Accumulator;
import gov.nasa.jpl.aerie.contrib.models.Clock;
import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.contrib.models.counters.Counter;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.foomissionmodel.models.Imager;
import gov.nasa.jpl.aerie.foomissionmodel.models.ImagerMode;
import gov.nasa.jpl.aerie.foomissionmodel.models.SimpleData;
import gov.nasa.jpl.aerie.foomissionmodel.models.TimeTrackerDaemon;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.resources.real.RealResource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

public final class Mission {
  // Need a way to pose constraints against activities, and generally modeling activity behavior with resources.
  // Need a clear story for external models.
  // Need to generalize RealDynamics to nonlinear polynomials.

  public final Register<Double> foo = Register.forImmutable(0.0);
  public final Register<Boolean> startingAfterUnixEpoch;
  public final Accumulator data = new Accumulator();
  public final Accumulator source = new Accumulator(100.0, 1.0);
  public final Accumulator sink;
  public final SimpleData simpleData = new SimpleData();
  public final Counter<Integer> activitiesExecuted = Counter.ofInteger(0);
  public final Imager complexData = new Imager(5, ImagerMode.LOW_RES, 30);

  public final RealResource combo = this.data.plus(this.data.rate);

  public final Clock utcClock = new Clock(Instant.parse("2023-08-18T00:00:00.00Z"));
  private final Registrar cachedRegistrar; // Normally bad practice, only stored to demonstrate built/unbuilt check

  public final TimeTrackerDaemon timeTrackerDaemon = new TimeTrackerDaemon();

  public final Counter<Integer> counter = Counter.ofInteger();

  public Mission(final Registrar registrar, final Instant planStart, final Configuration config) {
    this.cachedRegistrar = registrar;

    this.startingAfterUnixEpoch = Register.forImmutable(planStart.compareTo(Instant.EPOCH) > 0);
    this.sink = new Accumulator(0.0, config.sinkRate);

    spawn(this::test);

    // Assert mission model is unbuilt
    if (registrar.isInitializationComplete())
      throw new AssertionError("Registrar should not report initialization as complete");

    // TODO: Move resource registration out into an Aerie-specific binding layer.
    //   (This binding layer should also be the one responsible for feeding in constructor parameters.)
    registrar.discrete("/foo", this.foo, new DoubleValueMapper());
    registrar.discrete("/foo/conflicted", this.foo::isConflicted, new BooleanValueMapper());
    registrar.discrete("/foo/starting_after_unix_epoch", startingAfterUnixEpoch, new BooleanValueMapper());
    registrar.real("/batterySoC", this.source.minus(this.sink));
    registrar.real("/data", this.data);
    registrar.real("/data/rate", this.data.rate);
    registrar.real("/source", this.source);
    registrar.real("/source/rate", this.source.rate);
    registrar.real("/sink", this.sink);
    registrar.real("/sink/rate", this.sink.rate);
    registrar.discrete("/activitiesExecuted", this.activitiesExecuted, new IntegerValueMapper());
    registrar.real("/utcClock", this.utcClock.ticks);

    registrar.real("/simple_data/a/volume", this.simpleData.a.volume);
    registrar.real("/simple_data/a/rate", this.simpleData.a.rate);
    registrar.real("/simple_data/b/volume", this.simpleData.b.volume);
    registrar.real("/simple_data/b/rate", this.simpleData.b.rate);
    registrar.real("/simple_data/total_volume", this.simpleData.totalVolume);

    registrar.discrete("/counter", this.counter, new IntegerValueMapper());

    spawn(timeTrackerDaemon::run);

    spawn(replaying(new Runnable() {
      @Override
      public void run() { // Register a never-ending daemon task
        for (int i = 0; i < 1000; i++) {
          ModelActions.delay(Duration.SECOND);
        }
        counter.add(1);
        spawn(replaying(this));
      }
    }));

    if(config.raiseException) {
      spawn(() -> {
        delay(Duration.HOUR);
        throw new RuntimeException("Daemon task exception raised.");
      });
    }
  }

  public void test() {
    this.foo.set(21.0);
    this.data.rate.add(42.0);
    this.simpleData.a.activate();
    this.simpleData.b.activate();

    // Assert mission model is built
    if (!cachedRegistrar.isInitializationComplete())
      throw new AssertionError("Registrar should report initialization as complete");
  }
}

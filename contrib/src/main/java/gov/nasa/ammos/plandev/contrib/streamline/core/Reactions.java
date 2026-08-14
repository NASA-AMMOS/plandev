package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.dynamicsChange;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.updates;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Context.contextualized;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.when;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.replaying;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.waitUntil;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

/**
 * Utilities to create lightweight looping tasks,
 * usually spawned as daemons during modeling construction,
 * to "react" to important events in the simulation.
 *
 * <p>
 *     All reactions use the most efficient task setup for lightweight, short-lived tasks.
 *     At present, this means a trampolining-replaying task setup.
 *     Do not mutate state outside of cells across reaction iterations; this may produce nondeterminism or faults.
 * </p>
 */
public final class Reactions {
  private Reactions() {}

  public static void whenever(Resource<Discrete<Boolean>> conditionResource, Runnable action) {
    whenever(when(conditionResource), action);
  }

  public static void whenever(Condition condition, Runnable action) {
    whenever(() -> condition, action);
  }

  public static void whenever(Supplier<Condition> trigger, Runnable action) {
    final Condition condition = trigger.get();
    // Use replaying tasks to avoid threading overhead.
    spawn(replaying(contextualized(() -> {
      waitUntil(condition);
      action.run();
      // Trampoline off this task to avoid replaying.
      whenever(trigger, action);
    })));
  }

  // Special case for dynamicsChange condition, since it's non-obvious that this needs to be run in lambda form
  public static <D extends Dynamics<?, D>> void wheneverDynamicsChange(Resource<D> resource, Consumer<ErrorCatching<Expiring<D>>> reaction) {
    whenever(() -> dynamicsChange(resource), () -> reaction.accept(resource.getDynamics()));
  }

  /**
   * Run reaction whenever resource {@link Resources#updates}.
   * Note there is a 1-tick blindspot when using this method;
   * if there are updates on back-to-back simulation ticks in the same instant,
   * only the first triggers reaction.
   * See {@link Resources#updates} for a common pattern to mitigate this shortcoming.
   */
  public static <D extends Dynamics<?, D>> void wheneverUpdates(Resource<D> resource, Runnable reaction) {
    whenever(() -> updates(resource), reaction);
  }

  /**
   * Run reaction whenever resource {@link Resources#updates},
   * with a 1-tick delay to mitigate the {@link Resources#updates} blindspot.
   * See {@link Resources#updates} for a discussion of this shortcoming and its mitigations.
   */
  public static <D extends Dynamics<?, D>> void wheneverUpdates(Resource<D> resource, Consumer<ErrorCatching<Expiring<D>>> reaction) {
    whenever(() -> updates(resource), () -> {
      spawn(replaying(() -> {
      /*
        Spawn and delay zero, because we have a 1-tick blindspot when using "updates"

        Without the spawn/delay(0):
        Simulation ticks         resource updates         reaction task
                    0            update 0, delay(0)
                                                          "updates" condition satisfied
                    1            update 1                 reaction runs, sees resource update 0 ONLY, set "updates" condition again
                                                          "updates" condition unsatisfied

        With the spawn/delay(0):
        Simulation ticks         resource updates         approximate task
                    0            update 0, delay(0)
                                                          "updates" condition satisfied
                    1            update 1                 spawn task, set "updates" condition again
                                                          "updates" condition unsatisfied
                    2                                     reaction runs, sees resource update 1

        Updates spaced at least 2 ticks apart will be caught by the next "updates" condition.
       */
        delay(ZERO);
        reaction.accept(resource.getDynamics());
      }));
    });
  }

  public static void every(Duration period, Runnable action) {
    every(() -> period, action);
  }

  public static void every(Supplier<Duration> periodSupplier, Runnable action) {
    spawn(replaying(contextualized(() -> {
      delay(periodSupplier.get());
      action.run();
      every(periodSupplier, action);
    })));
  }
}

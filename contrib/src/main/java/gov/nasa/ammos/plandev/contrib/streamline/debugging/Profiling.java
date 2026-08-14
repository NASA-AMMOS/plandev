package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import gov.nasa.jpl.aerie.contrib.streamline.core.*;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.*;
import static java.lang.Math.*;
import static java.util.Comparator.comparingLong;

/**
 * Functions for profiling resources and conditions
 *
 * <p>
 *   WARNING! Profiling tools use static variables
 *   that are not re-initialized when the simulation restarts.
 *   This may give inconsistent or erroneous output in unit tests or scheduling.
 *   <p><em>Do not depend on profiling data for model behavior!</em></p>
 * </p>
 * <p>
 *   Additionally, all profiling methods short-circuit if a null or empty name is given.
 *   Profile only some invocations of a method by giving "" as a default profiling name
 *   in a non-profiled overload of the method, e.g.
 *   <pre>
 *     void someMethod(int a, String b) {
 *       // By passing "", we turn off profiling implicitly
 *       someMethod("", a, b);
 *     }
 *     void someMethod(String profilingName, int a, String b) {
 *       // some code that uses a profiling method
 *     }
 *   </pre>
 * </p>
 */
public final class Profiling {
  private Profiling() {}

  private static final long overallStartTime = System.nanoTime();

  /**
   * Cumulative count of profiled nanoseconds, used to account for nested profiled calls.
   */
  private static long cumulativeProfiledTime = 0;

  private static final Map<String, CallStats> resourceSamples = new HashMap<>();
  private static final Map<String, CallStats> conditionEvaluations = new HashMap<>();
  private static final Map<String, CallStats> taskExecutions = new HashMap<>();
  private static final Map<String, CallStats> effectsEmitted = new HashMap<>();

  private static final Comparator<CallStats> SORT_BY_CALLS_MADE = comparingLong(c -> -c.callsMade);
  private static final Comparator<CallStats> SORT_BY_OWN_NANOS = comparingLong(c -> -c.ownNanos);

  public static <D> Resource<D> profile(Resource<D> resource) {
    return profile(null, resource);
  }

  public static <D> Resource<D> profile(String name, Resource<D> resource) {
    Resource<D> result = new Resource<>() {
      private final Supplier<String> name$ = computeName(name, this);

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return resourceSamples.computeIfAbsent(name$.get(), k -> new CallStats())
                .accrue(resource::getDynamics);
      }
    };
    assignName("Resource", result, name, resource);
    return result;
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> profile(MutableResource<D> resource) {
    return profile(null, resource);
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> profile(String name, MutableResource<D> resource) {
    MutableResource<D> result = new MutableResource<>() {
      private final Supplier<String> name$ = computeName(name, this);

      @Override
      public void emit(DynamicsEffect<D> effect) {
        resource.emit(effect);
      }

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return resourceSamples.computeIfAbsent(name$.get(), k -> new CallStats())
                .accrue(resource::getDynamics);
      }
    };
    assignName("MutableResource", result, name, resource);
    return result;
  }

  public static Condition profile(Condition condition) {
    return profile(null, condition);
  }

  public static Condition profile(String name, Condition condition) {
    Condition result = new Condition() {
      private final Supplier<String> name$ = computeName(name, this);

      @Override
      public Optional<Duration> nextSatisfied(boolean positive, Duration atEarliest, Duration atLatest) {
        return accrue(conditionEvaluations, name$.get(), () -> condition.nextSatisfied(positive, atEarliest, atLatest));
      }
    };
    assignName("Condition", result, name, condition);
    return result;
  }

  public static Supplier<Condition> profile(Supplier<Condition> conditionSupplier) {
    return profile(null, conditionSupplier);
  }

  public static Supplier<Condition> profile(String name, Supplier<Condition> conditionSupplier) {
    return () -> profile(name, conditionSupplier.get());
  }

  public static Runnable profile(Runnable task) {
    return profile(null, task);
  }

  public static Runnable profile(String name, Runnable task) {
    return () -> profileTask(name, () -> { task.run(); return Unit.UNIT; });
  }

  public static <R> Supplier<R> profileTask(Supplier<R> task) {
    return profileTask(null, task);
  }

  public static <R> Supplier<R> profileTask(String name, Supplier<R> task) {
    Supplier<R> result = new Supplier<>() {
      private final Supplier<String> name$ = computeName(name, this);

      @Override
      public R get() {
        return accrue(taskExecutions, name$.get(), task);
      }
    };
    assignName("Task", result, name, task);
    return result;
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> profileEffects(MutableResource<D> resource) {
    MutableResource<D> result = new MutableResource<>() {
      @Override
      public void emit(DynamicsEffect<D> effect) {
        resource.emit(x -> accrue(effectsEmitted, getName(this, null), () -> effect.apply(x)));
      }

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return resource.getDynamics();
      }
    };
    assignName("MutableResource", result, null, resource);
    return result;
  }

  private static Supplier<String> computeName(String explicitName, Object profiledThing) {
    return explicitName != null
            ? () -> explicitName
            : () -> getName(profiledThing, null);
  }

  private static long ANONYMOUS_ID = 0;
  private static void assignName(String typeName, Object profiledThing, String explicitName, Object originalThing) {
    if (explicitName == null) {
      name(profiledThing, typeName + (ANONYMOUS_ID++) + " = %s", originalThing);
    } else {
      name(profiledThing, explicitName);
    }
  }

  private static <R> R accrue(Map<String, CallStats> statsMap, String name, Supplier<R> call) {
    return statsMap.computeIfAbsent(name, k -> new CallStats()).accrue(call);
  }

  public static void dump() {
    long overallElapsedNanos = System.nanoTime() - overallStartTime;
    System.out.printf("Overall time: %d ms%n", overallElapsedNanos / 1_000_000);
    if (!resourceSamples.isEmpty()) {
      System.out.println("Profiled resources:");
      dumpSampleMap(resourceSamples, overallElapsedNanos, SORT_BY_OWN_NANOS);
    }
    if (!conditionEvaluations.isEmpty()) {
      // Conditions are usually quick to evaluate, but trigger tasks and resource computation.
      // Therefore, calls are more important than time taken directly.
      System.out.println("Profiled conditions:");
      dumpSampleMap(conditionEvaluations, overallElapsedNanos, SORT_BY_CALLS_MADE);
    }
    if (!taskExecutions.isEmpty()) {
      System.out.println("Profiled tasks:");
      dumpSampleMap(taskExecutions, overallElapsedNanos, SORT_BY_OWN_NANOS);
    }
    if (!effectsEmitted.isEmpty()) {
      System.out.println("Profiled effects:");
      // Effects are usually quick to evaluate, but trigger tasks and resource computation.
      // Therefore, calls are more important than time taken directly.
      dumpSampleMap(effectsEmitted, overallElapsedNanos, SORT_BY_CALLS_MADE);
    }
  }

  private static final int MAX_NAME_LENGTH = 60;
  private static void dumpSampleMap(Map<String, CallStats> map, long overallElapsedNanos, Comparator<CallStats> sortBy) {
    final var nameLength = min(MAX_NAME_LENGTH, max(5, map.keySet().stream().mapToInt(String::length).max().orElse(1)));
    final var totalCalls = map.values().stream().mapToLong(c1 -> c1.callsMade).sum();
    final var totalNanos = map.values().stream().mapToLong(c1 -> c1.ownNanos).sum();
    final var callsLength = max(5, String.valueOf(totalCalls).length());
    final var millisLength = max(7, String.valueOf(totalNanos / 1_000_000).length());
    final var titleFormat =
        "  %-" + nameLength + "s  |"
        + "  %" + callsLength + "s %7s  |"
        + "  %" + millisLength + "s %7s  |"
        + "  %" + millisLength + "s %7s %7s %7s"
        + "%n";
    final var lineFormat =
        "  %-" + nameLength + "s  |"
        + "  %" + callsLength + "d  %5.1f%%  |"
        + "  %" + millisLength + "d  %5.1f%%  |"
        + "  %" + millisLength + "d  %5.1f%%  %5.1f%%  %5.1f%%"
        + "%n";
    System.out.printf(
        titleFormat,
        "Name",
        "Calls",
        "%Total",
        "Call ms",
        "%All",
        "Self ms",
        "%Call",
        "%Total",
        "%All");
    System.out.printf(
        lineFormat,
        "Total",
        totalCalls,
        100.0,
        // Adding up "total" times isn't sensible, since it multiple-counts time
        0,
        Double.NaN,
        // Adding up "self" times gives total profiled time
        totalNanos / 1_000_000,
        100.0,
        100.0,
        100.0 * totalNanos / overallElapsedNanos);
    map.entrySet()
       .stream()
       .sorted((a, b) -> sortBy.compare(a.getValue(), b.getValue()))
       .forEachOrdered(entry -> {
         var stats = entry.getValue();
         System.out.printf(
             lineFormat,
             fit(entry.getKey(), nameLength),
             stats.callsMade,
             100.0 * stats.callsMade / totalCalls,
             stats.totalNanos / 1_000_000,
             100.0 * stats.totalNanos / overallElapsedNanos,
             stats.ownNanos / 1_000_000,
             100.0 * stats.ownNanos / stats.totalNanos,
             100.0 * stats.ownNanos / totalNanos,
             100.0 * stats.ownNanos / overallElapsedNanos);
       });
  }

  private static final String TRUNCATED_INDICATOR = " ...";
  private static String fit(String s, int maxNameLength) {
    return s.length() <= maxNameLength
            ? s
            : s.substring(0, maxNameLength - TRUNCATED_INDICATOR.length()) + TRUNCATED_INDICATOR;
  }

  private static final class CallStats {
    public long callsMade = 0;
    public long totalNanos = 0;
    public long ownNanos = 0;

    public void accrue(Runnable call) {
      accrue(() -> { call.run(); return Unit.UNIT; });
    }

    public <R> R accrue(Supplier<R> call) {
      long startCumulative = cumulativeProfiledTime;
      long start = System.nanoTime();
      var result = call.get();
      long end = System.nanoTime();
      long endCumulative = cumulativeProfiledTime;

      long totalNanosInThisCall = end - start;
      long totalNanosInSubCalls = endCumulative - startCumulative;
      long ownNanosInThisCall = totalNanosInThisCall - totalNanosInSubCalls;

      ++callsMade;
      totalNanos += totalNanosInThisCall;
      ownNanos += ownNanosInThisCall;
      cumulativeProfiledTime += ownNanosInThisCall;

      return result;
    }
  }
}

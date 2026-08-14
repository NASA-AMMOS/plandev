package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RootfindingTest {
  final Duration zeroSecond = Duration.of(0, Duration.SECONDS);
  final static Duration oneSecond = Duration.of(1, Duration.SECONDS);
  final Duration twoSecond = Duration.of(2, Duration.SECONDS);
  final Duration threeSecond = Duration.of(3, Duration.SECONDS);
  final Duration thirtySecond = Duration.of(30, Duration.SECONDS);

  //compared with the testSimpleDiscontinuous, the function is discontinuous for all odd x, rootfinding is hitting
  //discontinuous values multiple times
  @Test
  void testHighlyDiscontinuous()
  throws EquationSolvingAlgorithms.ZeroDerivativeException, EquationSolvingAlgorithms.NoSolutionException,
         EquationSolvingAlgorithms.ExceededMaxIterationException, EquationSolvingAlgorithms.DivergenceException,
         EquationSolvingAlgorithms.InfiniteDerivativeException, SchedulingInterruptedException
  {
    final var durationFunctionDiscontinuousAtEverySecond =
        new EquationSolvingAlgorithms.Function<Duration, PrioritySolver.ActivityMetadata>() {
          @Override
          public Duration valueAt(
              final Duration x,
              final EquationSolvingAlgorithms.History<Duration, PrioritySolver.ActivityMetadata> historyType)
          throws EquationSolvingAlgorithms.DiscontinuityException
          {
            if (x.in(Duration.MICROSECONDS) % 2 != 0) {
              throw new EquationSolvingAlgorithms.DiscontinuityException();
            }
            final var ret = x.times(2);
            historyType.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(x, ret), null);
            return ret;
          }
        };
    final var alg = new EquationSolvingAlgorithms.SecantDurationAlgorithm<PrioritySolver.ActivityMetadata>();
    final var history = new PrioritySolver.HistoryWithActivity();
    final var solution = alg.findRoot(
        durationFunctionDiscontinuousAtEverySecond,
        history,
        oneSecond,
        Duration.of(39, Duration.SECONDS).plus(121, Duration.MICROSECONDS),
        Duration.of(50, Duration.MICROSECONDS),
        Duration.of(50, Duration.MICROSECONDS),
        zeroSecond,
        thirtySecond,
        100);
    assertEquals(3, solution.history().getHistory().size());
    assertEquals(new EquationSolvingAlgorithms.FunctionCoordinate<>(Duration.of(19500060, Duration.MICROSECONDS), Duration.of(39000120, Duration.MICROSECONDS)), solution.functionCoordinate());
  }


  @Test
  //this is reproducing issue 1139 :
  // f(x0) throws an exception + inf val which leads in the end to a ZeroDerivative
  public void testSimpleDiscontinuous()
  throws EquationSolvingAlgorithms.ZeroDerivativeException, EquationSolvingAlgorithms.NoSolutionException,
         EquationSolvingAlgorithms.ExceededMaxIterationException, EquationSolvingAlgorithms.DivergenceException,
         EquationSolvingAlgorithms.InfiniteDerivativeException, SchedulingInterruptedException
  {
    final var alg = new EquationSolvingAlgorithms.SecantDurationAlgorithm<PrioritySolver.ActivityMetadata>();

    //function only discontinuous at x = 1
    final var durationFunctionDiscontinuousAtOne =
        new EquationSolvingAlgorithms.Function<Duration, PrioritySolver.ActivityMetadata>() {
          @Override
          public Duration valueAt(
              final Duration x,
              final EquationSolvingAlgorithms.History<Duration, PrioritySolver.ActivityMetadata> historyType)
          throws EquationSolvingAlgorithms.DiscontinuityException
          {
            if (x.equals(oneSecond)) {
              throw new EquationSolvingAlgorithms.DiscontinuityException();
            }
            final var ret = x.times(2);
            historyType.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(x, ret), null);
            return ret;
          }
        };

    final var history = new PrioritySolver.HistoryWithActivity();
    final var solution = alg.findRoot(
        durationFunctionDiscontinuousAtOne,
        history,
        oneSecond,
        Duration.of(3, Duration.SECONDS).plus(500, Duration.MILLISECONDS),
        Duration.of(50, Duration.MICROSECONDS),
        Duration.of(50, Duration.MICROSECONDS),
        zeroSecond,
        threeSecond,
        10);
    assertEquals(3, solution.history().getHistory().size());
    assertEquals(new EquationSolvingAlgorithms.FunctionCoordinate<>(Duration.of(1750000, Duration.MICROSECONDS), Duration.of(3500000, Duration.MICROSECONDS)), solution.functionCoordinate());
  }

  @Test
  public void squareZeros()
  throws EquationSolvingAlgorithms.ZeroDerivativeException,
         EquationSolvingAlgorithms.ExceededMaxIterationException,
         EquationSolvingAlgorithms.InfiniteDerivativeException, SchedulingInterruptedException
  {
    final var alg = new EquationSolvingAlgorithms.SecantDurationAlgorithm<PrioritySolver.ActivityMetadata>();
    //f(x) = x^2
    final var squareFunc =
        new EquationSolvingAlgorithms.Function<Duration, PrioritySolver.ActivityMetadata>() {
          @Override
          public Duration valueAt(
              final Duration x,
              final EquationSolvingAlgorithms.History<Duration, PrioritySolver.ActivityMetadata> historyType) {
            final var ret = Duration.of((long) Math.pow(x.in(Duration.MICROSECONDS),2), Duration.MICROSECONDS);
            historyType.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(x, ret), null);
            return ret;
          }
        };

    final var history = new PrioritySolver.HistoryWithActivity();
    final var solution = alg.findRoot(
        squareFunc,
        history,
        Duration.of(-2, Duration.SECONDS),
        Duration.of(0, Duration.MICROSECONDS),
        Duration.of(0, Duration.MICROSECONDS),
        Duration.of(-2, Duration.SECONDS),
        twoSecond,
        100);
    assertEquals(30, solution.history().getHistory().size());
    assertEquals(new EquationSolvingAlgorithms.FunctionCoordinate<>(Duration.of(0, Duration.MICROSECONDS), Duration.of(0, Duration.MICROSECONDS)), solution.functionCoordinate());
  }


  @Test
  public void floorZeros()
  throws EquationSolvingAlgorithms.ZeroDerivativeException,
         EquationSolvingAlgorithms.ExceededMaxIterationException,
         EquationSolvingAlgorithms.InfiniteDerivativeException, SchedulingInterruptedException
  {
    final var alg = new EquationSolvingAlgorithms.SecantDurationAlgorithm<PrioritySolver.ActivityMetadata>();
    final var floorFunc =
        new EquationSolvingAlgorithms.Function<Duration, PrioritySolver.ActivityMetadata>() {
          @Override
          public Duration valueAt(
              final Duration x,
              final EquationSolvingAlgorithms.History<Duration, PrioritySolver.ActivityMetadata> historyType)
          throws EquationSolvingAlgorithms.DiscontinuityException
          {
            if(x.in(Duration.SECONDS) == 1) throw new EquationSolvingAlgorithms.DiscontinuityException();
            final var ret = Duration.of(x.dividedBy(Duration.SECONDS), Duration.SECONDS);
            historyType.add(new EquationSolvingAlgorithms.FunctionCoordinate<>(x, ret), null);
            return ret;
          }
        };

    final var history = new PrioritySolver.HistoryWithActivity();
    final var solution = alg.findRoot(
        floorFunc,
        history,
        Duration.of(-5, Duration.SECONDS),
        Duration.of(0, Duration.MICROSECONDS),
        Duration.of(0, Duration.MICROSECONDS),
        Duration.of(-2, Duration.SECONDS),
        twoSecond,
        100);
    assertEquals(1, solution.history().getHistory().size());
    assertEquals(new EquationSolvingAlgorithms.FunctionCoordinate<>(Duration.of(-860925, Duration.MICROSECONDS), Duration.of(0, Duration.MICROSECONDS)), solution.functionCoordinate());
  }
}

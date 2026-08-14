package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public class EquationSolvingAlgorithms {

  private static final Logger logger = LoggerFactory.getLogger(EquationSolvingAlgorithms.class);

  public record FunctionCoordinate<T>(T x, T fx){}

  public record RootFindingResult<T, Metadata>(FunctionCoordinate<T> functionCoordinate, History<T, Metadata> history){}

  /**
   * Solves f(x) = y for x in [xLow, xHigh] with confidence interval [yLow, yHigh] around y such that we stop when
   * value y is in [y-yLow, y+yHigh].
   * x0 and x1 are initial guesses for x, they must be close to the solution to avoid diverging
   * It is considered that algorithm is diverging when the iterated value of x goes out of [xLow, xHigh].
   */
  public interface SecantAlgorithm<T, Metadata>{
    RootFindingResult<T, Metadata> findRoot(Function<T, Metadata> f,
                                  History<T, Metadata> history,
                                  T x0,
                                  T y,
                                  T toleranceYLow,
                                  T toleranceYHigh,
                                  T xLow,
                                  T xHigh,
                                  int maxNbIterations) throws
                                                       ZeroDerivativeException,
                                                       InfiniteDerivativeException,
                                                       DivergenceException,
                                                       ExceededMaxIterationException,
                                                       NoSolutionException,
                                                       SchedulingInterruptedException;
  }

  public interface Function<T, Metadata> {
    T valueAt(T x, History<T, Metadata> history) throws DiscontinuityException, SchedulingInterruptedException;
  }

  public interface History<T, Metadata>{
    void add(FunctionCoordinate<T> functionCoordinate, Metadata metadata);
    List<Pair<FunctionCoordinate<T>, Optional<Metadata>>> getHistory();
    Optional<Pair<FunctionCoordinate<T>, Optional<Metadata>>> getLastEvent();
    boolean alreadyVisited(T x);
  }

  public static class ZeroDerivativeException extends Exception{
    public ZeroDerivativeException() {}
  }

  public static class DiscontinuityException extends Exception{
    public DiscontinuityException(){}
  }

  public static class InfiniteDerivativeException extends Exception{
    public InfiniteDerivativeException() {}
  }

  public static class DivergenceException extends Exception{
    public DivergenceException(String errorMessage) {
      super(errorMessage);
    }
  }

  public static class ExceededMaxIterationException extends Exception{
    public ExceededMaxIterationException() {
      super();
    }
  }

  public static class NoSolutionException extends Exception{
    public NoSolutionException() {
      super();
    }
  }

  public static class SecantDurationAlgorithm<Metadata> implements SecantAlgorithm<Duration, Metadata>{

    private final RandomGenerator randomGenerator = RandomGeneratorFactory.of("Random").create(956756789);

    /**
     * Randomly selects a value in the interval [bound1, bound2]
     * @param bound1 the first bound
     * @param bound2 the second bound
     * @return a value chosen randomly
     */
    private Duration chooseRandomX(final Duration bound1, final Duration bound2){
      var low = bound1;
      var high = bound2;
      if(low.equals(high)) return low;
      if(bound1.longerThan(bound2)) { low = bound2; high = bound1; }
      return Duration.of(
          randomGenerator.nextLong(low.in(Duration.MICROSECONDS), high.in(Duration.MICROSECONDS)),
          Duration.MICROSECONDS);
    }

    private record IteratingResult(FunctionCoordinate<Duration> result, int nbIterationsPerformed){}

    /**
     * Querying Function.valueAt may lead to a discontinuity. This procedure starts at an initial x value
     * and stops only when the value returned is not a discontinuity or the maximum number of iterations has been reached
     * Kind of an infaillible valueAt with a limited number of iterations
     * @param function the function we are trying to call
     * @param init the initial x value
     * @param min the lower bound of the domain of x
     * @param max the upper bound of the domain of x
     * @param history the querying history of f
     * @param maxIteration the maximum number of iteration possible
     * @return a coordinate (x, f(x)) s.t. f is continuous at x.
     * @throws ExceededMaxIterationException
     */
    private IteratingResult nextValueAt(
        final Function<Duration, Metadata> function,
        final Duration init,
        final Duration min,
        final Duration max,
        final History<Duration, Metadata> history,
        final int maxIteration)
    throws ExceededMaxIterationException, SchedulingInterruptedException
    {
      // the number of possible values may be less than the number of iterations, so stop after all have been visited.
      long numTimepoints = minus(minus(max.in(Duration.MICROSECONDS), min.in(Duration.MICROSECONDS)), 1);
      long maxIters = Long.min(maxIteration, numTimepoints);
      var cur = init;
      long i = 0;
      do {
        //we should not come back to previously visited values
        if (!history.alreadyVisited(cur) && cur.between(min, max)) {
          i++;
          try {
            final var value = function.valueAt(cur, history);
            return new IteratingResult(new FunctionCoordinate<>(cur, value), ((Long)i).intValue());
          } catch (DiscontinuityException e) {
            //nothing, keep iterating
          }
        }
        cur = chooseRandomX(min, max);
        //if all timepoints have been visited, another call to random will have no effect and thus we should exit
      } while(i < maxIters);
      throw new ExceededMaxIterationException();
    }

    /**
     * Addition of longs with safety from overflow
     */
    private static long plus(final long rd1, final long rd2 ) {
      long result;
      // check for overflow
      if ( rd1 >= 0 && Long.MAX_VALUE - rd1 <= rd2 ) {
        result = Long.MAX_VALUE;
      } else if ( rd1 < 0 && Long.MIN_VALUE - rd1 >= rd2 ) {
        result = Long.MIN_VALUE;
      } else {
        result = rd1 + rd2;
      }
      return result;
    }

    /**
     * Subtraction of longs with safety from overflow
     */
    private static long minus(final long rd1, final long rd2 ) {
      long result;
      // use plus, but don't risk trying to negate +/-inf
      if ( rd2 == Long.MAX_VALUE ) result = plus( rd1, Long.MIN_VALUE );
      else if ( rd2 == Long.MIN_VALUE ) result = plus( rd1, Long.MAX_VALUE );
      else result = plus(rd1, -rd2);
      return result;
    }

    /**
     * Solves x s.t. f(x) = y by transforming it to the equivalent rootfinding problem x s.t. f(x) - y = 0
     * @param f the function
     * @param history
     * @param x0 one of the initial x value
     * @param y the objective
     * @param toleranceYLow absolute value of the tolerance below 0
     * @param toleranceYHigh absolute value of the tolerance above 0
     * @param xLow the lower bound for x
     * @param xHigh the upper bound for x
     * @param maxNbIterations the maximum number of iterations possible
     * @return the solution to the equation, throws an exception otherwise
     * @throws ZeroDerivativeException
     * @throws NoSolutionException
     * @throws ExceededMaxIterationException
     * @throws DivergenceException
     * @throws InfiniteDerivativeException
     */
    public RootFindingResult<Duration, Metadata> findRoot(
        final Function<Duration, Metadata> f,
        final History<Duration, Metadata> history,
        final Duration x0,
        final Duration y,
        final Duration toleranceYLow,
        final Duration toleranceYHigh,
        final Duration xLow,
        final Duration xHigh,
        final int maxNbIterations)
    throws ZeroDerivativeException, NoSolutionException, ExceededMaxIterationException, DivergenceException,
           InfiniteDerivativeException, SchedulingInterruptedException
    {
      final var ff = new EquationSolvingAlgorithms.Function<Duration, Metadata>(){
        @Override
        public Duration valueAt(final Duration x, final History<Duration, Metadata> history)
        throws EquationSolvingAlgorithms.DiscontinuityException, SchedulingInterruptedException
        {
          // don't add value to history -- it breaks things
          return f.valueAt(x, history).minus(y);
        }
      };

      final var result = new EquationSolvingAlgorithms
          .SecantDurationAlgorithm<Metadata>()
          .findRoot(
              ff,
              history,
              x0,
              toleranceYLow,
              toleranceYHigh,
              xLow,
              xHigh,
              maxNbIterations);
      return new RootFindingResult<>(new FunctionCoordinate<>(result.functionCoordinate.x(), result.functionCoordinate.fx().plus(y)), result.history);
    }

    /**
     * Solves x s.t. f(x) = 0
     */
    public RootFindingResult<Duration, Metadata> findRoot(
        final Function<Duration, Metadata> f,
        final History<Duration, Metadata> history,
        final Duration x0,
        final Duration toleranceYLow,
        final Duration toleranceYHigh,
        final Duration xLow,
        final Duration xHigh,
        final int maxNbIterations)
    throws ZeroDerivativeException, InfiniteDerivativeException, ExceededMaxIterationException,
           SchedulingInterruptedException
    {
      final var xLow_long = xLow.in(Duration.MICROSECONDS);
      final var xHigh_long = xHigh.in(Duration.MICROSECONDS);
      final var resultX0 = nextValueAt(f, x0, xLow, xHigh, history, maxNbIterations);
      int nbItPerformed = resultX0.nbIterationsPerformed();
      var ff_x_nminus1 = resultX0.result().fx();
      var x_nminus1 = resultX0.result().x();
      double x_nminus1_double = x_nminus1.in(Duration.MICROSECONDS);

      //We check whether the initial bounds might satisfy the exit criteria.
      if (ff_x_nminus1.between(Duration.negate(toleranceYLow), toleranceYHigh)) {
        return new RootFindingResult<>(new FunctionCoordinate<>(x_nminus1, ff_x_nminus1), history);
      }
      //optimistic heuristic based on the first evaluation: we assume the duration of the activity is constant
      var x_n = x_nminus1.minus(ff_x_nminus1);
      final var resultX1 = nextValueAt(f, x_n, xLow, xHigh, history, maxNbIterations - nbItPerformed);
      nbItPerformed += resultX0.nbIterationsPerformed();
      var ff_x_n = resultX1.result().fx();
      x_n = resultX1.result().x();
      double x_n_double = x_n.in(Duration.MICROSECONDS);
      if (ff_x_n.between(Duration.negate(toleranceYLow), toleranceYHigh)) {
        return new RootFindingResult<>(new FunctionCoordinate<>(x_n, ff_x_n), history);
      }
      while (nbItPerformed < maxNbIterations) {
        //(f(xn) - f(xn_m1)) / (xn - xn_m1)
        final double localDerivative =
            (float) (ff_x_n.minus(ff_x_nminus1)).in(Duration.MICROSECONDS) / (x_n_double - x_nminus1_double);
        if (localDerivative == 0) throw new ZeroDerivativeException();
        x_nminus1_double = x_n_double;
        ff_x_nminus1 = ff_x_n;
        //Note : xn_m2 is implicit here as it is used only for computing the derivative
        //localDerivative has been computed with what is now xn_m1 and xn_m2
        x_n_double = x_n_double - (ff_x_nminus1.in(Duration.MICROSECONDS) / localDerivative);
        x_nminus1 = x_n;
        x_n = Duration.of((long) x_n_double, Duration.MICROSECONDS);
        if (x_n.equals(x_nminus1)) throw new InfiniteDerivativeException();
        final var resultXn = nextValueAt(f, x_n, xLow, xHigh, history, maxNbIterations - nbItPerformed);
        nbItPerformed += resultXn.nbIterationsPerformed();
        ff_x_n = resultXn.result().fx();
        x_n = resultXn.result().x();
        x_n_double = x_n.in(Duration.MICROSECONDS);

        //The final solution needs to be in the given bounds which is why this check is added here.
        if (ff_x_n.between(Duration.negate(toleranceYLow), toleranceYHigh) &&
            (x_n_double >= xLow_long && x_n_double <= xHigh_long)){
          logger.debug("Root found after " + nbItPerformed + " iterations");
          return new RootFindingResult<>(new FunctionCoordinate<>(x_n, ff_x_n), history);
        }
      }
      throw new ExceededMaxIterationException();
    }
  }
}

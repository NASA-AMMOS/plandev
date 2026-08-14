package gov.nasa.jpl.aerie.json;

import org.apache.commons.lang3.tuple.Pair;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A set of utility functions for transforming the fields of an object parser.
 *
 * <p> The parsers produced from {@link ProductParsers} work with iteratively nested {@link Pair}s, which are
 * inconvenient to work with directly. The static functions in this class let you use an n-ary function where
 * a nested tuple would normally be given, or return an n-tuple rather than a series of nested 2-tuples. </p>
 */
public final class Uncurry {
  private Uncurry() {}

  @FunctionalInterface
  public interface Function3<Result, T1, T2, T3> {
    Result apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Function4<Result, T1, T2, T3, T4> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Function5<Result, T1, T2, T3, T4, T5> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Function6<Result, T1, T2, T3, T4, T5, T6> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }

  @FunctionalInterface
  public interface Function7<Result, T1, T2, T3, T4, T5, T6, T7> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7);
  }

  @FunctionalInterface
  public interface Function8<Result, T1, T2, T3, T4, T5, T6, T7, T8> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8);
  }

  public interface Function9<Result, T1, T2, T3, T4, T5, T6, T7, T8, T9> {
    Result apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9);
  }

  public static <Result, T1>
  Function<T1, Result>
  untuple(Function<T1, Result> f) {
    return f;
  }

  public static <Result, T1, T2>
  Function<Pair<T1, T2>, Result>
  untuple(BiFunction<T1, T2, Result> f) {
    return p -> f.apply(
        p.getLeft(),
        p.getRight());
  }

  public static <Result, T1, T2, T3>
  Function<Pair<Pair<T1, T2>, T3>, Result>
  untuple(Function3<Result, T1, T2, T3> f) {
    return p -> f.apply(
        p.getLeft().getLeft(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4>
  Function<Pair<Pair<Pair<T1, T2>, T3>, T4>, Result>
  untuple(Function4<Result, T1, T2, T3, T4> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4, T5>
  Function<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, Result>
  untuple(Function5<Result, T1, T2, T3, T4, T5> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4, T5, T6>
  Function<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, Result>
  untuple(Function6<Result, T1, T2, T3, T4, T5, T6> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4, T5, T6, T7>
  Function<Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>, Result>
  untuple(Function7<Result, T1, T2, T3, T4, T5, T6, T7> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4, T5, T6, T7, T8>
  Function<Pair<Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>, T8>, Result>
  untuple(Function8<Result, T1, T2, T3, T4, T5, T6, T7, T8> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <Result, T1, T2, T3, T4, T5, T6, T7, T8, T9>
  Function<Pair<Pair<Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>, T8>, T9>, Result>
  untuple(Function9<Result, T1, T2, T3, T4, T5, T6, T7, T8, T9> f) {
    return p -> f.apply(
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getLeft().getRight(),
        p.getLeft().getLeft().getRight(),
        p.getLeft().getRight(),
        p.getRight());
  }

  public static <T1>
  T1
  tuple(T1 t1) {
    return t1;
  }

  public static <T1, T2>
  Pair<T1, T2>
  tuple(T1 t1, T2 t2) {
    return Pair.of(t1, t2);
  }

  public static <T1, T2, T3>
  Pair<Pair<T1, T2>, T3>
  tuple(T1 t1, T2 t2, T3 t3) {
    return tuple(tuple(t1, t2), t3);
  }

  public static <T1, T2, T3, T4>
  Pair<Pair<Pair<T1, T2>, T3>, T4>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4) {
    return tuple(tuple(tuple(t1, t2), t3), t4);
  }

  public static <T1, T2, T3, T4, T5>
  Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5) {
    return tuple(tuple(tuple(tuple(t1, t2), t3), t4), t5);
  }

  public static <T1, T2, T3, T4, T5, T6>
  Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6) {
    return tuple(tuple(tuple(tuple(tuple(t1, t2), t3), t4), t5), t6);
  }

  public static <T1, T2, T3, T4, T5, T6, T7>
  Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7) {
    return tuple(tuple(tuple(tuple(tuple(tuple(t1, t2), t3), t4), t5), t6), t7);
  }

  public static <T1, T2, T3, T4, T5, T6, T7, T8>
  Pair<Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>, T8>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8) {
    return tuple(tuple(tuple(tuple(tuple(tuple(tuple(t1, t2), t3), t4), t5), t6), t7), t8);
  }

  public static <T1, T2, T3, T4, T5, T6, T7, T8, T9>
  Pair<Pair<Pair<Pair<Pair<Pair<Pair<Pair<T1, T2>, T3>, T4>, T5>, T6>, T7>, T8>, T9>
  tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9) {
    return tuple(tuple(tuple(tuple(tuple(tuple(tuple(tuple(t1, t2), t3), t4), t5), t6), t7), t8), t9);
  }
}

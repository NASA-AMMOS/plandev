package gov.nasa.jpl.aerie.contrib.streamline.core.monads;

import gov.nasa.jpl.aerie.contrib.streamline.core.ErrorCatching;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.ThinResource;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;
import gov.nasa.jpl.aerie.contrib.streamline.utils.*;
import org.apache.commons.lang3.function.TriFunction;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Dependencies.addDependency;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.argsFormat;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling.profile;
import static gov.nasa.jpl.aerie.contrib.streamline.utils.FunctionalUtils.curry;

/**
 * Monad A -> Resource&lt;A&gt;.
 * This is the primary monad for model authors,
 * handling both expiry and "stitching together" of derived resources.
 */
public final class ResourceMonad {
  private ResourceMonad() {}

  private static boolean profileAllResources = false;
  /**
   * Turn on profiling for all getDynamics calls on {@link Resource}s derived through {@link ResourceMonad}.
   *
   * <p>
   *     Calling this method once before constructing your model will profile getDynamics on every derived resource.
   *     Profiling may be compute and/or memory intensive, and should not be used in production.
   * </p>
   * <p>
   *     If only a few cells are suspect, you can also call {@link Profiling#profile}
   *     directly on just those resource, rather than profiling every resource.
   * </p>
   * <p>
   *     Call {@link Profiling#dump()} to see results.
   * </p>
   */
  public static void profileAllResources() {
    profileAllResources = true;
  }

  public static <A> Resource<A> pure(A a) {
    Resource<A> result = ThinResourceMonad.pure(DynamicsMonad.pure(a))::getDynamics;
    if (profileAllResources) result = profile(result);
    return result;
  }

  public static <A, B> Resource<B> apply(Resource<A> a, Resource<Function<A, B>> f) {
    Resource<B> result = ThinResourceMonad.apply(a, ThinResourceMonad.map(f, DynamicsMonad::apply))::getDynamics;
    addDependency(result, a);
    addDependency(result, f);
    if (profileAllResources) result = profile(result);
    return result;
  }

  private static <A> ThinResource<ErrorCatching<Expiring<A>>> distribute(ErrorCatching<Expiring<ThinResource<A>>> a) {
    return () -> DynamicsMonad.map(a, ThinResource::getDynamics);
  }

  public static <A> Resource<A> join(Resource<Resource<A>> a) {
    // Perform a trivial down-conversion to the base ThinResource types, to expose the Dynamics wrappers in the type signature.
    ThinResource<ErrorCatching<Expiring<ThinResource<ErrorCatching<Expiring<A>>>>>> a$ = map(a, $ -> $);
    // Then use distributivity and basic joins to collapse the type.
    // The ::getDynamics at the end up-converts back to Resource, from ThinResource
    Resource<A> result = ThinResourceMonad.map(ThinResourceMonad.join(ThinResourceMonad.map(a$, ResourceMonad::distribute)), DynamicsMonad::join)::getDynamics;
    addDependency(result, a);
    if (profileAllResources) result = profile(result);
    return result;
  }

  /**
   * Efficient reduce for resources, lifting an operator that can reduce the dynamics.
   * <p>
   *     This is logically equivalent to, but more efficient than,
   *     <pre>operands.stream().reduce(pure(identity), map(operator), map(operator))</pre>
   *     That is, it's logically equivalent to lifting the operator to an operator on resources,
   *     then reducing the resources with the lifted operator.
   *     However, that would produce a large number of unnecessary intermediate resources.
   *     This function creates a "flat" reduction, with no intermediate nodes.
   * </p>
   *
   * @see ResourceMonad#reduce(Collection, Object, BiFunction, String)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction, String)
   */
  public static <A> Resource<A> reduce(Collection<? extends Resource<A>> operands, A identity, BiFunction<A, A, A> f) {
    return reduce(operands, DynamicsMonad.pure(identity), DynamicsMonad.map(f));
  }

  /**
   * Like {@link ResourceMonad#reduce(Collection, Object, BiFunction)}, but also names the result
   * like "operationName(operand1, operand2, ...)".
   *
   * @see ResourceMonad#reduce(Collection, Object, BiFunction)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction, String)
   */
  public static <A> Resource<A> reduce(Collection<? extends Resource<A>> operands, A identity, BiFunction<A, A, A> f, String operationName) {
    return reduce(operands, DynamicsMonad.pure(identity), DynamicsMonad.map(f), operationName);
  }

  /**
   * Like {@link ResourceMonad#reduce(Collection, Object, BiFunction)},
   * but operator acts on fully wrapped dynamics instead of plain dynamics.
   *
   * @see ResourceMonad#reduce(Collection, Object, BiFunction)
   * @see ResourceMonad#reduce(Collection, Object, BiFunction, String)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction, String)
   */
  public static <A> Resource<A> reduce(Collection<? extends Resource<A>> operands, ErrorCatching<Expiring<A>> identity, BiFunction<ErrorCatching<Expiring<A>>, ErrorCatching<Expiring<A>>, ErrorCatching<Expiring<A>>> f) {
    Resource<A> result = ThinResourceMonad.reduce(operands, identity, f)::getDynamics;
    operands.forEach(op -> addDependency(result, op));
    return result;
  }

  /**
   * Like {@link ResourceMonad#reduce(Collection, Object, BiFunction, String)},
   * but operator acts on fully wrapped dynamics instead of plain dynamics.
   *
   * @see ResourceMonad#reduce(Collection, Object, BiFunction)
   * @see ResourceMonad#reduce(Collection, Object, BiFunction, String)
   * @see ResourceMonad#reduce(Collection, ErrorCatching, BiFunction)
   */
  public static <A> Resource<A> reduce(Collection<? extends Resource<A>> operands, ErrorCatching<Expiring<A>> identity, BiFunction<ErrorCatching<Expiring<A>>, ErrorCatching<Expiring<A>>, ErrorCatching<Expiring<A>>> f, String operationName) {
    return name(reduce(operands, identity, f), operationName + argsFormat(operands), operands.toArray());
  }

  // Not strictly part of this monad, but commonly used to "fill the gap" when deriving resources with partial bindings
  public static <A> Resource<A> pure(Expiring<A> a) {
    return ThinResourceMonad.pure(ErrorCatchingMonad.pure(a))::getDynamics;
  }

  public static <A> Resource<A> pure(ErrorCatching<Expiring<A>> a) {
    return ThinResourceMonad.pure(a)::getDynamics;
  }

  // GENERATED CODE START
  // Supplemental methods generated by generate_monad_methods.py on 2023-12-06.
  
  public static <A, B> Function<Resource<A>, Resource<B>> apply(Resource<Function<A, B>> f) {
    return a -> apply(a, f);
  }
  
  public static <A, B> Resource<B> map(Resource<A> a, Function<A, B> f) {
    return apply(a, pure(f));
  }
  
  public static <A, B> Function<Resource<A>, Resource<B>> map(Function<A, B> f) {
    return apply(pure(f));
  }
  
  public static <A, B> Resource<B> bind(Resource<A> a, Function<A, Resource<B>> f) {
    return join(map(a, f));
  }
  
  public static <A, B> Function<Resource<A>, Resource<B>> bind(Function<A, Resource<B>> f) {
    return a -> bind(a, f);
  }
  
  public static <A, B, Result> Resource<Result> map(Resource<A> a, Resource<B> b, BiFunction<A, B, Result> function) {
    return map(a, b, curry(function));
  }
  
  public static <A, B, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Function<A, Function<B, Result>> function) {
    return apply(b, map(a, function));
  }
  
  public static <A, B, Result> BiFunction<Resource<A>, Resource<B>, Resource<Result>> map(BiFunction<A, B, Result> function) {
    return (a, b) -> map(a, b, function);
  }
  
  public static <A, B, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, BiFunction<A, B, Resource<Result>> function) {
    return join(map(a, b, function));
  }
  
  public static <A, B, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Function<A, Function<B, Resource<Result>>> function) {
    return join(map(a, b, function));
  }
  
  public static <A, B, Result> BiFunction<Resource<A>, Resource<B>, Resource<Result>> bind(BiFunction<A, B, Resource<Result>> function) {
    return (a, b) -> bind(a, b, function);
  }
  
  public static <A, B, C, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, TriFunction<A, B, C, Result> function) {
    return map(a, b, c, curry(function));
  }
  
  public static <A, B, C, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Function<A, Function<B, Function<C, Result>>> function) {
    return apply(c, map(a, b, function));
  }
  
  public static <A, B, C, Result> TriFunction<Resource<A>, Resource<B>, Resource<C>, Resource<Result>> map(TriFunction<A, B, C, Result> function) {
    return (a, b, c) -> map(a, b, c, function);
  }
  
  public static <A, B, C, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, TriFunction<A, B, C, Resource<Result>> function) {
    return join(map(a, b, c, function));
  }
  
  public static <A, B, C, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Function<A, Function<B, Function<C, Resource<Result>>>> function) {
    return join(map(a, b, c, function));
  }
  
  public static <A, B, C, Result> TriFunction<Resource<A>, Resource<B>, Resource<C>, Resource<Result>> bind(TriFunction<A, B, C, Resource<Result>> function) {
    return (a, b, c) -> bind(a, b, c, function);
  }
  
  public static <A, B, C, D, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Function4<A, B, C, D, Result> function) {
    return map(a, b, c, d, curry(function));
  }
  
  public static <A, B, C, D, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Function<A, Function<B, Function<C, Function<D, Result>>>> function) {
    return apply(d, map(a, b, c, function));
  }
  
  public static <A, B, C, D, Result> Function4<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<Result>> map(Function4<A, B, C, D, Result> function) {
    return (a, b, c, d) -> map(a, b, c, d, function);
  }
  
  public static <A, B, C, D, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Function4<A, B, C, D, Resource<Result>> function) {
    return join(map(a, b, c, d, function));
  }
  
  public static <A, B, C, D, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Function<A, Function<B, Function<C, Function<D, Resource<Result>>>>> function) {
    return join(map(a, b, c, d, function));
  }
  
  public static <A, B, C, D, Result> Function4<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<Result>> bind(Function4<A, B, C, D, Resource<Result>> function) {
    return (a, b, c, d) -> bind(a, b, c, d, function);
  }
  
  public static <A, B, C, D, E, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Function5<A, B, C, D, E, Result> function) {
    return map(a, b, c, d, e, curry(function));
  }
  
  public static <A, B, C, D, E, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Function<A, Function<B, Function<C, Function<D, Function<E, Result>>>>> function) {
    return apply(e, map(a, b, c, d, function));
  }
  
  public static <A, B, C, D, E, Result> Function5<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<Result>> map(Function5<A, B, C, D, E, Result> function) {
    return (a, b, c, d, e) -> map(a, b, c, d, e, function);
  }
  
  public static <A, B, C, D, E, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Function5<A, B, C, D, E, Resource<Result>> function) {
    return join(map(a, b, c, d, e, function));
  }
  
  public static <A, B, C, D, E, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Function<A, Function<B, Function<C, Function<D, Function<E, Resource<Result>>>>>> function) {
    return join(map(a, b, c, d, e, function));
  }
  
  public static <A, B, C, D, E, Result> Function5<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<Result>> bind(Function5<A, B, C, D, E, Resource<Result>> function) {
    return (a, b, c, d, e) -> bind(a, b, c, d, e, function);
  }
  
  public static <A, B, C, D, E, F, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Function6<A, B, C, D, E, F, Result> function) {
    return map(a, b, c, d, e, f, curry(function));
  }
  
  public static <A, B, C, D, E, F, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Result>>>>>> function) {
    return apply(f, map(a, b, c, d, e, function));
  }
  
  public static <A, B, C, D, E, F, Result> Function6<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<Result>> map(Function6<A, B, C, D, E, F, Result> function) {
    return (a, b, c, d, e, f) -> map(a, b, c, d, e, f, function);
  }
  
  public static <A, B, C, D, E, F, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Function6<A, B, C, D, E, F, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, function));
  }
  
  public static <A, B, C, D, E, F, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Resource<Result>>>>>>> function) {
    return join(map(a, b, c, d, e, f, function));
  }
  
  public static <A, B, C, D, E, F, Result> Function6<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<Result>> bind(Function6<A, B, C, D, E, F, Resource<Result>> function) {
    return (a, b, c, d, e, f) -> bind(a, b, c, d, e, f, function);
  }
  
  public static <A, B, C, D, E, F, G, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Function7<A, B, C, D, E, F, G, Result> function) {
    return map(a, b, c, d, e, f, g, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Result>>>>>>> function) {
    return apply(g, map(a, b, c, d, e, f, function));
  }
  
  public static <A, B, C, D, E, F, G, Result> Function7<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<Result>> map(Function7<A, B, C, D, E, F, G, Result> function) {
    return (a, b, c, d, e, f, g) -> map(a, b, c, d, e, f, g, function);
  }
  
  public static <A, B, C, D, E, F, G, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Function7<A, B, C, D, E, F, G, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, function));
  }
  
  public static <A, B, C, D, E, F, G, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Resource<Result>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, function));
  }
  
  public static <A, B, C, D, E, F, G, Result> Function7<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<Result>> bind(Function7<A, B, C, D, E, F, G, Resource<Result>> function) {
    return (a, b, c, d, e, f, g) -> bind(a, b, c, d, e, f, g, function);
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Function8<A, B, C, D, E, F, G, H, Result> function) {
    return map(a, b, c, d, e, f, g, h, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Result>>>>>>>> function) {
    return apply(h, map(a, b, c, d, e, f, g, function));
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Function8<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<Result>> map(Function8<A, B, C, D, E, F, G, H, Result> function) {
    return (a, b, c, d, e, f, g, h) -> map(a, b, c, d, e, f, g, h, function);
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Function8<A, B, C, D, E, F, G, H, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, function));
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Resource<Result>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, function));
  }
  
  public static <A, B, C, D, E, F, G, H, Result> Function8<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<Result>> bind(Function8<A, B, C, D, E, F, G, H, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h) -> bind(a, b, c, d, e, f, g, h, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Function9<A, B, C, D, E, F, G, H, I, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Result>>>>>>>>> function) {
    return apply(i, map(a, b, c, d, e, f, g, h, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Function9<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<Result>> map(Function9<A, B, C, D, E, F, G, H, I, Result> function) {
    return (a, b, c, d, e, f, g, h, i) -> map(a, b, c, d, e, f, g, h, i, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Function9<A, B, C, D, E, F, G, H, I, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Resource<Result>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, Result> Function9<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<Result>> bind(Function9<A, B, C, D, E, F, G, H, I, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i) -> bind(a, b, c, d, e, f, g, h, i, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Function10<A, B, C, D, E, F, G, H, I, J, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Result>>>>>>>>>> function) {
    return apply(j, map(a, b, c, d, e, f, g, h, i, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Function10<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<Result>> map(Function10<A, B, C, D, E, F, G, H, I, J, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j) -> map(a, b, c, d, e, f, g, h, i, j, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Function10<A, B, C, D, E, F, G, H, I, J, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Resource<Result>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, Result> Function10<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<Result>> bind(Function10<A, B, C, D, E, F, G, H, I, J, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j) -> bind(a, b, c, d, e, f, g, h, i, j, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Function11<A, B, C, D, E, F, G, H, I, J, K, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Result>>>>>>>>>>> function) {
    return apply(k, map(a, b, c, d, e, f, g, h, i, j, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Function11<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<Result>> map(Function11<A, B, C, D, E, F, G, H, I, J, K, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k) -> map(a, b, c, d, e, f, g, h, i, j, k, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Function11<A, B, C, D, E, F, G, H, I, J, K, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Resource<Result>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, Result> Function11<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<Result>> bind(Function11<A, B, C, D, E, F, G, H, I, J, K, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k) -> bind(a, b, c, d, e, f, g, h, i, j, k, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Function12<A, B, C, D, E, F, G, H, I, J, K, L, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Result>>>>>>>>>>>> function) {
    return apply(l, map(a, b, c, d, e, f, g, h, i, j, k, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Function12<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<Result>> map(Function12<A, B, C, D, E, F, G, H, I, J, K, L, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l) -> map(a, b, c, d, e, f, g, h, i, j, k, l, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Function12<A, B, C, D, E, F, G, H, I, J, K, L, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Resource<Result>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, Result> Function12<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<Result>> bind(Function12<A, B, C, D, E, F, G, H, I, J, K, L, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Function13<A, B, C, D, E, F, G, H, I, J, K, L, M, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Result>>>>>>>>>>>>> function) {
    return apply(m, map(a, b, c, d, e, f, g, h, i, j, k, l, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Function13<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<Result>> map(Function13<A, B, C, D, E, F, G, H, I, J, K, L, M, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Function13<A, B, C, D, E, F, G, H, I, J, K, L, M, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Resource<Result>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, Result> Function13<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<Result>> bind(Function13<A, B, C, D, E, F, G, H, I, J, K, L, M, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Function14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Result>>>>>>>>>>>>>> function) {
    return apply(n, map(a, b, c, d, e, f, g, h, i, j, k, l, m, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Function14<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<Result>> map(Function14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Function14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Resource<Result>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result> Function14<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<Result>> bind(Function14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Function15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Result>>>>>>>>>>>>>>> function) {
    return apply(o, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Function15<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<Result>> map(Function15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Function15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Resource<Result>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result> Function15<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<Result>> bind(Function15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Function16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Result>>>>>>>>>>>>>>>> function) {
    return apply(p, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Function16<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Result>> map(Function16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Function16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Resource<Result>>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result> Function16<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Result>> bind(Function16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Function17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Result>>>>>>>>>>>>>>>>> function) {
    return apply(q, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Function17<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<Result>> map(Function17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Function17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Resource<Result>>>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result> Function17<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<Result>> bind(Function17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Function18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Result>>>>>>>>>>>>>>>>>> function) {
    return apply(r, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Function18<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<Result>> map(Function18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Function18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Resource<Result>>>>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result> Function18<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<Result>> bind(Function18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Function19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Function<S, Result>>>>>>>>>>>>>>>>>>> function) {
    return apply(s, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Function19<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<S>, Resource<Result>> map(Function19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Function19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Function<S, Resource<Result>>>>>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result> Function19<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<S>, Resource<Result>> bind(Function19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Resource<T> t, Function20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> function) {
    return map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, curry(function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Resource<Result> map(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Resource<T> t, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Function<S, Function<T, Result>>>>>>>>>>>>>>>>>>>> function) {
    return apply(t, map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Function20<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<S>, Resource<T>, Resource<Result>> map(Function20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t) -> map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, function);
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Resource<T> t, Function20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Resource<Result>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Resource<Result> bind(Resource<A> a, Resource<B> b, Resource<C> c, Resource<D> d, Resource<E> e, Resource<F> f, Resource<G> g, Resource<H> h, Resource<I> i, Resource<J> j, Resource<K> k, Resource<L> l, Resource<M> m, Resource<N> n, Resource<O> o, Resource<P> p, Resource<Q> q, Resource<R> r, Resource<S> s, Resource<T> t, Function<A, Function<B, Function<C, Function<D, Function<E, Function<F, Function<G, Function<H, Function<I, Function<J, Function<K, Function<L, Function<M, Function<N, Function<O, Function<P, Function<Q, Function<R, Function<S, Function<T, Resource<Result>>>>>>>>>>>>>>>>>>>>> function) {
    return join(map(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, function));
  }
  
  public static <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result> Function20<Resource<A>, Resource<B>, Resource<C>, Resource<D>, Resource<E>, Resource<F>, Resource<G>, Resource<H>, Resource<I>, Resource<J>, Resource<K>, Resource<L>, Resource<M>, Resource<N>, Resource<O>, Resource<P>, Resource<Q>, Resource<R>, Resource<S>, Resource<T>, Resource<Result>> bind(Function20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Resource<Result>> function) {
    return (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t) -> bind(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, function);
  }
  // GENERATED CODE END
}

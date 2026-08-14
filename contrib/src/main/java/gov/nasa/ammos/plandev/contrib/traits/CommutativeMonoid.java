package gov.nasa.jpl.aerie.contrib.traits;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.function.BinaryOperator;

/**
 * An effect algebra for combining order-independent effects.
 *
 * <p>
 * A commutative monoid on a type T is an operation {@code combine()} whose order of operands doesn't matter,
 * together with a value {@code empty()} that acts as identity for that operation. Symbolically:
 * </p>
 *
 * <ul>
 *   <li>{@code combine} is associative: <code>combine(combine(x, y), z) == combine(x, combine(y, z))</code></li>
 *   <li>{@code combine} is commutative: <code>combine(x, y) == combine(y, x)</code></li>
 *   <li>{@code empty} is the identity: <code>combine(empty(), x) == x == combine(x, empty())</code></li>
 * </ul>
 *
 * <p>
 *   Any commutative monoid can be made into an {@link EffectTrait} by using the same operation for both sequential
 *   and concurrent composition.
 * </p>
 *
 * @see EffectTrait
 */
public record CommutativeMonoid<T> (T empty, BinaryOperator<T> combinator)
    implements EffectTrait<T>
{
  @Override
  public T sequentially(final T prefix, final T suffix) {
    return this.combinator.apply(prefix, suffix);
  }

  @Override
  public T concurrently(final T left, final T right) {
    return this.combinator.apply(left, right);
  }
}

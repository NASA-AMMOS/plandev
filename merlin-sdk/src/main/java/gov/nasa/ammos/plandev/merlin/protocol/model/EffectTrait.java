package gov.nasa.jpl.aerie.merlin.protocol.model;

/**
 * A trait for performing effect-algebraic operations on a type.
 *
 * <p>
 * We define an <b>effect type</b> to be a type whose values can be combined sequentially or concurrently, and which has
 * a known value representing the absence of an effect. An implementation of {@code EffectTrait<P>} provides
 * these operations for a type <code>P</code>, even if the definition of <code>P</code> itself cannot be changed.
 * We will often refer to an implementation of {@code EffectTrait<P>} as "an effect algebra on <code>P</code>".
 * </p>
 *
 * <p>
 * A useful graphical notation is to write <code>x | y</code> for <code>trait.concurrently(x, y)</code> (when
 * <code>trait</code> is fixed), and <code>x; y</code> for <code>trait.sequentially(x, y)</code>. Nested calls
 * to these methods can then be understood as expressions over these algebraic operators.
 * </p>
 *
 * <p>
 * Implementors are required to obey the following contract, up to observable behavior. This contract captures
 * the common-sense behavior expected of effects that occur over time.
 * </p>
 * <ul>
 *   <li>{@code sequentially} is associative: <code>(x; y); z == x; (y; z)</code></li>
 *   <li>{@code concurrently} is associative: <code>(x | y) | z == x | (y | z)</code></li>
 *   <li>{@code concurrently} is commutative: <code>x | y == y | x</code></li>
 *   <li>{@code empty} is the identity for sequential composition: <code>empty(); x == x == x; empty()</code></li>
 *   <li>{@code empty} is the identity for concurrent composition: <code>empty() | x == x == x | empty()</code></li>
 * </ul>
 *
 * @param <Effect> The type on which this object gives an effect algebra.
 */
public interface EffectTrait<Effect> {
  Effect empty();
  Effect sequentially(Effect prefix, Effect suffix);
  Effect concurrently(Effect left, Effect right);
}

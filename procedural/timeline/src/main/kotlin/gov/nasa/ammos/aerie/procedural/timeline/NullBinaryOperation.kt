package gov.nasa.ammos.aerie.procedural.timeline

/**
 * A generalized binary operation interface for maybe-null operands.
 *
 * This is a function interface for the [invoke] method, which takes in maybe-null
 * left and right operands, and outputs a result.
 *
 * ## Construction Helper Functions
 *
 * Helper functions for constructing binary operations with common patterns are available
 * in this interface's companion object [here][Companion]. Unfortunately, Kotlin's documentation
 * generator Dokka doesn't like to show companion object methods inside interfaces, but all these
 * methods can be called just like a static method (i.e. `NullBinaryOperation.combineOrNull(...)`).
 */
fun interface NullBinaryOperation<in Left, in Right, out Out> {

  /**
   * Calculate the operation.
   *
   * @param l left operand; can be null, usually when the operand has a gap.
   * @param r right operand; can be null, usually when the operand has a gap.
   * @param i interval on which the operation is being calculated.
   */
  operator fun invoke(l: Left?, r: Right?, i: Interval): Out

  /** Helper functions for constructing binary operations. */
  companion object {
    /**
     * Constructs an operation from three different cases:
     * - when only the left operand is present
     * - when only the right operand is present
     * - when both operands are present
     */
    @JvmStatic fun <Left, Right, Out> cases(
        left: (Left & Any, Interval) -> Out,
        right: (Right & Any, Interval) -> Out,
        combine: (Left & Any, Right & Any, Interval) -> Out
    ) = NullBinaryOperation<Left, Right, Out> { l, r, i ->
      if (l != null && r != null) combine(l, r, i)
      else if (l != null) left(l, i)
      else if (r != null) right(r, i)
      else throw BinaryOperationBothNullException()
    }

    /** A named version of the default constructor. */
    @JvmStatic fun <Left, Right, Out> singleFunction(f: (Left?, Right?, Interval) -> Out) = NullBinaryOperation(f)

    /**
     * Constructs an operation that combines the operands in some way if they are both present,
     * and produces null if either operand is null.
     *
     * @param f operation to be invoked when both operands are present.
     */
    @JvmStatic fun <Left, Right, Out> combineOrNull(f: (Left & Any, Right & Any, Interval) -> Out) = NullBinaryOperation<Left, Right, Out?> { l, r, i ->
      if (l == null || r == null) null
      else f(l, r, i)
    }

    /**
     * Constructs an operation that combines the operands of equal type if they are both present.
     * If either operand is not present, the other is passed through unchanged.
     *
     * This means that both operands and the output must all be the same type.
     */
    @JvmStatic fun <V> combineOrIdentity(f: (V & Any, V & Any, Interval) -> V) = NullBinaryOperation<V, V, V> { l, r, i ->
      if (l != null && r != null) f(l, r, i)
      else l ?: r ?: throw BinaryOperationBothNullException()
    }

    /**
     * Constructs a binary operation for a reduce-style timeline operation.
     *
     * This pattern uses an input and output type, which in most cases will be equal.
     * The code that invokes this operation will keep track of an "accumulator", which may start uninitialized.
     * If the accumulator is `null`, the "convert" function is used to take one input and create the accumulator.
     * If the accumulator is defined, the "combine" function is used to combine it with new inputs until the input is consumed.
     * The output is the final value of the accumulator.
     *
     * @param convert Converts an input into the accumulator.
     * @param combine Combines the accumulator with a new input.
     *
     * See [gov.nasa.ammos.aerie.procedural.timeline.ops.ParallelOps.reduceIntoProfile] for an example of where it should be used.
     */
    @JvmStatic fun <In, Out> reduce(
        convert: (new: In & Any, Interval) -> Out,
        combine: (new: In & Any, acc: Out & Any, Interval) -> Out
    ) = NullBinaryOperation<In, Out, Out> { new, acc, i ->
      if (acc != null && new != null) combine(new, acc, i)
      else if (new != null) convert(new, i)
      else acc ?: throw BinaryOperationBothNullException()
    }

    /**
     * Constructs a binary operation which passes operands through unchanged if only one is present.
     *
     * Throws [ZipOperationBothDefinedException] if both operands are present.
     */
    @JvmStatic fun <In> zip() = NullBinaryOperation<In, In, In> { l, r, _ ->
      if (l != null && r != null) throw ZipOperationBothDefinedException()
      else l ?: (r ?: throw BinaryOperationBothNullException())
    }

    /**
     * Constructs a binary operation which converts either operand to the output if only one is present.
     *
     * Throws [ZipOperationBothDefinedException] if both operands are present.
     */
    @JvmStatic fun <Left, Right, Out> convertZip(
        left: (Left & Any, Interval) -> Out,
        right: (Right & Any, Interval) -> Out,
    ) = NullBinaryOperation<Left, Right, Out> { l, r, i ->
      if (l != null && r != null) throw ZipOperationBothDefinedException()
      else if (l != null) left(l, i)
      else if (r != null) right(r, i)
      else throw BinaryOperationBothNullException()
    }
  }

  /** Thrown if both arguments in a binary operation are null. */
  class BinaryOperationBothNullException: Exception("Both arguments to binary operation were null.")
  /** Thrown by [zip] if both arguments to the binary operation are defined. */
  class ZipOperationBothDefinedException: Exception("Both arguments to zip binary operation were defined.")
}

package gov.nasa.jpl.aerie.constraints;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.Violation;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;

import java.util.Iterator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Assertions {
  private Assertions() {}

  public static <T> void assertEquivalent(final Expression<T> expected, Expression<T> result) {
    assertEquals(expected, result);
  }

  public static void assertEquivalent(final LinearProfile expected, final LinearProfile actual) {
    assertEquals(expected, actual);

    assertTrue(areEquivalent(expected.profilePieces, actual.profilePieces));
  }

  public static void assertEquivalent(final DiscreteProfile expected, final DiscreteProfile actual) {
    assertEquals(expected, actual);

    assertTrue(areEquivalent(expected.profilePieces, actual.profilePieces));
  }

  public static void assertEquivalent(final Violation expected, final Violation actual) {
    assertEquals(expected, actual);

    assertTrue(areEquivalent(expected.activityInstanceIds(), actual.activityInstanceIds()));
    assertTrue(areEquivalent(expected.windows(), actual.windows()));
  }

  public static void assertEquivalent(final Windows expected, final Windows actual) {
    assertEquals(expected, actual);

    // Things that are equal ought to be observationally equivalent.
    assertTrue(areEquivalent(expected, actual));
  }

  public static <T> void assertEquivalent(final Iterable<T> xs, final Iterable<T> ys) {
    assertTrue(areEquivalent(xs, ys));
  }

  private static <T> boolean areEquivalent(final Iterable<T> xs, final Iterable<T> ys) {
    return areEquivalent(xs.iterator(), ys.iterator());
  }

  private static <T> boolean areEquivalent(final Iterator<T> xsIter, final Iterator<T> ysIter) {
    while (true) {
      if (!xsIter.hasNext()) return !ysIter.hasNext();
      if (!ysIter.hasNext()) return false;

      final var x = xsIter.next();
      final var y = ysIter.next();
      if (!Objects.equals(x, y)) return false;
    }
  }
}

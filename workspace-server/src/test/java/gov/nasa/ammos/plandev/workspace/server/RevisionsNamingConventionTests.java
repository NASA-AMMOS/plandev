package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.types.RevisionNamingConvention;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

public class RevisionsNamingConventionTests {
  @Nested
  class Alphabetical {
    private final RevisionNamingConvention alphaRevision = RevisionNamingConvention.ALPHABETICAL;
    // This is equal to 26^6 + 26^5 + 26^4 + 26^3 + 26^2 + 26
    private static final int MAX_ORDINAL = 321272406;

    /**
     * Ordinals given to the name function must be between 1 and 26^6, inclusive.
     * Anything outside of those bounds produce an IllegalArgumentException.
     */
    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, MAX_ORDINAL+1, Integer.MAX_VALUE})
    void outOfBoundsOrdinals(int ordinal) {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> alphaRevision.name(ordinal));
      assertEquals("Ordinal %d is out of range [1, %d]".formatted(ordinal, MAX_ORDINAL), ex.getMessage());
    }

    /**
     * Names with no characters, more than 6 valid characters, and invalid characters
     * all produce an IllegalArgumentException.
     */
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"aaaaaaa", "thisisfartoomanycharacters", "aa bb", "%$%%@", "1", "\n"})
    void outOfBoundsNames(String name) {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> alphaRevision.ordinal(name));
      assertEquals("Invalid revision name \"%s\": expected 1-6 lowercase letters.".formatted(name), ex.getMessage());
    }

    @Test
    void nullName() {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> alphaRevision.ordinal(null));
      assertEquals("Parameter 'name' must not be null.", ex.getMessage());
    }

    /**
     * Names and Ordinals have a bijective relationship: name(ordinal) = ordinal(name)
     */
    @ParameterizedTest
    @MethodSource("acceptedNamesOrdinalsStream")
    void namesAreBijective(String name, int ordinal) {
      assertEquals(name, alphaRevision.name(ordinal));
      assertEquals(ordinal, alphaRevision.ordinal(name));
    }

    private static Stream<Arguments> acceptedNamesOrdinalsStream() {
      return Stream.of(
          Arguments.arguments("a", 1),
          Arguments.arguments("b", 2),
          Arguments.arguments("y", 25),
          Arguments.arguments("z", 26),
          Arguments.arguments("aa", 27),
          Arguments.arguments("ab", 28),
          Arguments.arguments("az", 52),
          Arguments.arguments("ba", 53),
          Arguments.arguments("zz", 702), // 26^2 + 26
          Arguments.arguments("aaaaaa", 12356631),
          Arguments.arguments("zzzzzz", MAX_ORDINAL)
      );
    }
  }

  @Nested
  class Numerical {
    private final RevisionNamingConvention numericRevision = RevisionNamingConvention.NUMERIC;

    /**
     * Ordinals given to the name function must be at least 1.
     */
    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -100, -1, 0})
    void outOfBoundsOrdinals(int ordinal) {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> numericRevision.name(ordinal));
      assertEquals("Parameter 'ordinal' must be at least 1.", ex.getMessage());
    }

    /**
     * Names must parse to an integer value. If it doesn't, it produces an Illegal Argument Exception
     */
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"2147483648", "thisisnotanumber", "aa bb", "%`%%@", "1 000 000", "\n"})
    void badParseNames(String name) {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> numericRevision.ordinal(name));
      assertEquals("Parameter 'name' contains invalid data. Expected an integer, received: "+name, ex.getMessage());
    }

    /**
     * Names must correspond to an integer value greater than 1. If it doesn't, it produces an Illegal Argument Exception
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-100", "-2147483648"})
    void outOfBoundsNames(String name) {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> numericRevision.ordinal(name));
      assertEquals("Invalid revision '%s': Revisions may not be less than 1.".formatted(name), ex.getMessage());
    }

    @Test
    void nullName() {
      final var ex = assertThrowsExactly(IllegalArgumentException.class, () -> numericRevision.ordinal(null));
      assertEquals("Parameter 'name' must not be null.", ex.getMessage());
    }

    /**
     * Names and Ordinals have a bijective relationship: name(ordinal) = ordinal(name)
     */
    @ParameterizedTest
    @MethodSource("acceptedNamesOrdinalsStream")
    void namesAreBijective(String name, int ordinal) {
      assertEquals(name, numericRevision.name(ordinal));
      assertEquals(ordinal, numericRevision.ordinal(name));
    }

    private static Stream<Arguments> acceptedNamesOrdinalsStream() {
      return Stream.of(
          Arguments.arguments("1", 1),
          Arguments.arguments("2", 2),
          Arguments.arguments("25", 25),
          Arguments.arguments("26", 26),
          Arguments.arguments("27", 27),
          Arguments.arguments("28", 28),
          Arguments.arguments("52", 52),
          Arguments.arguments("53", 53),
          Arguments.arguments("702", 702),
          Arguments.arguments("12356631", 12356631),
          Arguments.arguments("321272406", 321272406),
          Arguments.arguments("2147483647", Integer.MAX_VALUE)
      );
    }
  }
}

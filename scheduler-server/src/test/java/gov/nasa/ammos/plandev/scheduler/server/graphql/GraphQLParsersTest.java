package gov.nasa.jpl.aerie.scheduler.server.graphql;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphQLParsersTest {


  public static Stream<Arguments> parseGraphQLTimestamp() {
    return Stream.of(
        Arguments.of("2021-01-01T00:00:00+00:00", Timestamp.fromString("2021-001T00:00:00")),
        Arguments.of("2021-12-31T23:59:59+00:00", Timestamp.fromString("2021-365T23:59:59"))
    );
  }

  public static Stream<Arguments> parseGraphQLInterval() {
    return Stream.of(
        Arguments.of("-322:21:15.111", parseDurationISO8601("PT-322H-21M-15.111S")),
        Arguments.of("+322:21:15.111", parseDurationISO8601("PT322H21M15.111S")),
        Arguments.of("322:21:15.111", parseDurationISO8601("PT322H21M15.111S")),
        Arguments.of("322:21:15.", parseDurationISO8601("PT322H21M15S")),
        Arguments.of("322:21:15", parseDurationISO8601("PT322H21M15S")),
        Arguments.of("+322:21:15", parseDurationISO8601("PT322H21M15S")),
        Arguments.of("-322:21:15", parseDurationISO8601("PT-322H-21M-15S"))
    );
  }

  @ParameterizedTest
  @MethodSource
  void parseGraphQLTimestamp(String input, Timestamp expected) {
    final var actual = GraphQLParsers.parseGraphQLTimestamp(input);
    assertEquals(expected, actual);
  }

  @ParameterizedTest
  @MethodSource
  void parseGraphQLInterval(String input, Duration expected) {
    final var actual = GraphQLParsers.durationFromPGInterval(input);
    assertEquals(expected, actual);
  }

  private static Duration parseDurationISO8601(final String iso8601String){
    final var javaDuration = java.time.Duration.parse(iso8601String);
    return Duration.of((javaDuration.getSeconds() * 1_000_000L) + (javaDuration.getNano() / 1000L), Duration.MICROSECONDS);
  }

}

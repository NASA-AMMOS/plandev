package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

public record ProfileSet(
    Map<String, ResourceProfile<Optional<RealDynamics>>> realProfiles,
    Map<String, ResourceProfile<Optional<SerializedValue>>> discreteProfiles
) {
  public static ProfileSet ofNullable(
      final Map<String, ResourceProfile<Optional<RealDynamics>>> realProfiles,
      final Map<String, ResourceProfile<Optional<SerializedValue>>> discreteProfiles
  ) {
    return new ProfileSet(
        realProfiles,
        discreteProfiles
    );
  }
  public static ProfileSet of(
      final Map<String, ResourceProfile<RealDynamics>> realProfiles,
      final Map<String, ResourceProfile<SerializedValue>> discreteProfiles
  ) {
    return new ProfileSet(
        wrapInOptional(realProfiles),
        wrapInOptional(discreteProfiles)
    );
  }

  public static <T> Map<String, ResourceProfile<Optional<T>>> wrapInOptional(
      final Map<String, ResourceProfile<T>> profileMap
  ) {
    return profileMap
      .entrySet().stream()
      .map($ -> Pair.of(
          $.getKey(),
          ResourceProfile.of(
              $.getValue().schema(),
              $.getValue().segments()
               .stream()
               .map(segment -> new ProfileSegment<>(segment.extent(), Optional.of(segment.dynamics())))
               .toList())))
      .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  public static <T> Map<String, ResourceProfile<T>> unwrapOptional(
      final Map<String, ResourceProfile<Optional<T>>> profileMap
  ) throws NoSuchElementException {
    return profileMap
        .entrySet().stream()
        .map($ -> Pair.of(
            $.getKey(),
            ResourceProfile.of(
                $.getValue().schema(),
                $.getValue().segments()
                 .stream()
                 .map(segment -> new ProfileSegment<>(segment.extent(), segment.dynamics().get()))
                 .toList())))
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }
}

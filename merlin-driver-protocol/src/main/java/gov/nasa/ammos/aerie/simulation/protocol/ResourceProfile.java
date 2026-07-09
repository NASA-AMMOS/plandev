package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.List;

public record ResourceProfile<T> (ValueSchema schema, List<ProfileSegment<T>> segments) {
  public static <T> ResourceProfile<T> of(ValueSchema schema, List<ProfileSegment<T>> segments) {
    return new ResourceProfile<T>(schema, segments);
  }
}

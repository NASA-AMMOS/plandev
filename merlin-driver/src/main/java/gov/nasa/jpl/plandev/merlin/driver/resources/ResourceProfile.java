package gov.nasa.jpl.plandev.merlin.driver.resources;

import gov.nasa.jpl.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

import java.util.List;

public record ResourceProfile<T> (ValueSchema schema, List<ProfileSegment<T>> segments) {
  public static <T> ResourceProfile<T> of(ValueSchema schema, List<ProfileSegment<T>> segments) {
    return new ResourceProfile<T>(schema, segments);
  }
}

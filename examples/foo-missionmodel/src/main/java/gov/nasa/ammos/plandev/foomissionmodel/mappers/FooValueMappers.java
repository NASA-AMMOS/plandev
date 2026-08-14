package gov.nasa.ammos.plandev.foomissionmodel.mappers;

import gov.nasa.ammos.plandev.contrib.serialization.mappers.Vector3DValueMapper;
import gov.nasa.ammos.plandev.merlin.framework.ValueMapper;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

public class FooValueMappers {
  public static ValueMapper<Vector3D> vector3d(final ValueMapper<Double> elementMapper) {
    return new Vector3DValueMapper(elementMapper);
  }

  public static ValueMapper<Duration> duration(){
    return new SmartestDurationValueMapper();
  }
}

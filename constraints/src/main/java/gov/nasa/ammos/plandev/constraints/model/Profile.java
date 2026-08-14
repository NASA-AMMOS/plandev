package gov.nasa.ammos.plandev.constraints.model;

import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;

import java.util.Optional;

public interface Profile<P extends Profile<P>> {
  Windows equalTo(P other);
  Windows notEqualTo(P other);
  Windows changePoints();
  boolean isConstant();

  P assignGaps(P def);
  P shiftBy(Duration duration);

  Optional<SerializedValue> valueAt(Duration timepoint);
}

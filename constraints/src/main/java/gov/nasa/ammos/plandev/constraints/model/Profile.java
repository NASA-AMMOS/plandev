package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

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

package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

public interface TaskHandle {
  Scheduler delay(Duration delay);

  Scheduler call(InSpan inSpan, TaskFactory<?> child);

  Scheduler await(gov.nasa.jpl.aerie.merlin.protocol.model.Condition condition);
}

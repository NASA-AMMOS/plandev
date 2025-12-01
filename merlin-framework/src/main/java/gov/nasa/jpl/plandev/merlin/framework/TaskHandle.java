package gov.nasa.jpl.plandev.merlin.framework;

import gov.nasa.jpl.plandev.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.plandev.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.merlin.protocol.types.InSpan;

public interface TaskHandle {
  Scheduler delay(Duration delay);

  Scheduler call(InSpan inSpan, TaskFactory<?> child);

  Scheduler await(gov.nasa.jpl.plandev.merlin.protocol.model.Condition condition);
}

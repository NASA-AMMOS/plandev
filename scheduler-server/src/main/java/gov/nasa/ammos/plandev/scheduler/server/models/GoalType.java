package gov.nasa.ammos.plandev.scheduler.server.models;

import java.nio.file.Path;

public sealed interface GoalType {
  record EDSL(GoalSource source) implements GoalType {}
  record JAR(Path path) implements GoalType {}
}

package gov.nasa.jpl.aerie.scheduler.server.models;

import javax.json.JsonObject;
import java.nio.file.Path;

public sealed interface GoalType {
  record EDSL(String source) implements GoalType {}
  record JAR(Path path, String args) implements GoalType {}
}

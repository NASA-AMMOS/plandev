package gov.nasa.jpl.aerie.scheduler.server.models;

import java.util.Optional;

public record GoalId(long id, long revision, Optional<Long> goalInvocationId) {
  public GoalId(long id, long revision) {
    this(id, revision, Optional.empty());
  }
  public GoalId(long id, long revision, long goalInvocationId) {
    this(id, revision, Optional.of(goalInvocationId));
  }
}

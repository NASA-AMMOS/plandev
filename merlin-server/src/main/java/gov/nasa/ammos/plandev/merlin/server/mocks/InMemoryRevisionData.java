package gov.nasa.ammos.plandev.merlin.server.mocks;

import gov.nasa.ammos.plandev.merlin.server.services.RevisionData;

public record InMemoryRevisionData(
    long planRevision
) implements RevisionData {

  @Override
  public MatchResult matches(final RevisionData other) {
    if (!(other instanceof InMemoryRevisionData o)) return MatchResult.failure("RevisionData type mismatch");

    if (planRevision == o.planRevision()) {
      return MatchResult.success();
    } else {
      return MatchResult.failure("Plan revision mismatch");
    }
  }
}

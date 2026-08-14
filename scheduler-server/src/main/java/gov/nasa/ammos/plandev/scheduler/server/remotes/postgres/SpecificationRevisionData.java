package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.scheduler.server.services.RevisionData;

public record SpecificationRevisionData(long specificationRevision, long planRevision) implements RevisionData {
  @Override
  public MatchResult matches(final RevisionData other) {
    if (!(other instanceof SpecificationRevisionData o)) return MatchResult.failure("");

    if (this.specificationRevision != o.specificationRevision) {
      return MatchResult.failure("Specification Revision mismatch");
    }

    return MatchResult.success();
  }
}

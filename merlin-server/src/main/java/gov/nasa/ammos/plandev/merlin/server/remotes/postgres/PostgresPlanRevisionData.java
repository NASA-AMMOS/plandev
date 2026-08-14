package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.server.services.RevisionData;

import java.util.Optional;

public record PostgresPlanRevisionData(
    long modelRevision,
    long planRevision,
    long simulationRevision,
    Optional<Long> templateRevision
) implements RevisionData {
  @Override
  public MatchResult matches(final RevisionData other) {
    if (!(other instanceof PostgresPlanRevisionData o)) return MatchResult.failure("RevisionData type mismatch");

    if (planRevision != o.planRevision()) {
      return MatchResult.failure("Plan revision mismatch");
    } else if (modelRevision != o.modelRevision()) {
      return MatchResult.failure("Model revision mismatch");
    } else if (simulationRevision != o.simulationRevision()) {
      return MatchResult.failure("Simulation revision mismatch");
    } else if (!templateRevision.equals(o.templateRevision())) {
      return MatchResult.failure("Simulation template revision mismatch");
    } else {
      return MatchResult.success();
    }
  }
}

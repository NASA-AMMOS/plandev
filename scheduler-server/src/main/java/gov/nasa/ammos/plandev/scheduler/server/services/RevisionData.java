package gov.nasa.jpl.aerie.scheduler.server.services;

public interface RevisionData {
  sealed interface MatchResult {
    record Success() implements MatchResult {}
    record Failure(String reason) implements RevisionData.MatchResult {}

    static MatchResult.Success success() { return new MatchResult.Success(); }
    static MatchResult.Failure failure(String reason) { return new MatchResult.Failure(reason); }
  }

  MatchResult matches(RevisionData other);
}

package gov.nasa.jpl.aerie.merlin.server.services;

public interface RevisionData {
  sealed interface MatchResult {
    record Success() implements MatchResult {}
    record Failure(String reason) implements MatchResult {}

    static Success success() { return new Success(); }
    static Failure failure(String reason) { return new Failure(reason); }
  }

  MatchResult matches(RevisionData other);
}

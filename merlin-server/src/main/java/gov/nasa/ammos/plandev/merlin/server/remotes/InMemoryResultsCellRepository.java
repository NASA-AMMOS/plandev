package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationResultsHandle;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryResultsCellRepository implements ResultsCellRepository {
  public record Key(PlanId planId, long planRevision) {}

  private final Map<InMemoryResultsCellRepository.Key, InMemoryCell> cells = new HashMap<>();
  private final PlanRepository planRepository;

  public InMemoryResultsCellRepository(final PlanRepository planRepository) {
    this.planRepository = planRepository;
  }

  @Override
  public ResultsProtocol.OwnerRole allocate(final PlanId planId, final String requestedBy) {
    try {
      final var planRevision = planRepository.getPlanRevision(planId);
      final var cell = new InMemoryCell(planId, planRevision);
      this.cells.put(new InMemoryResultsCellRepository.Key(planId, planRevision), cell);
      return cell;
    } catch (final NoSuchPlanException ex) {
      throw new Error("Cannot allocate simulation cell for nonexistent plan", ex);
    }
  }

  @Override
  public ResultsProtocol.OwnerRole forceAllocate(PlanId planId, String requestedBy) {
    return allocate(planId, requestedBy);
  }

  @Override
  public Optional<ResultsProtocol.OwnerRole> claim(final PlanId planId, final Long datasetId) {
    return Optional.empty();
  }

  @Override
  public Optional<ResultsProtocol.ReaderRole> lookup(final PlanId planId) {
    try {
      final var planRevision = planRepository.getPlanRevision(planId);
      return Optional.ofNullable(this.cells.get(new InMemoryResultsCellRepository.Key(planId, planRevision)));
    } catch (final NoSuchPlanException ex) {
      throw new Error("Cannot allocate simulation cell for nonexistent plan", ex);
    }
  }

  @Override
  public Optional<ResultsProtocol.ReaderRole> lookup(final PlanId planId, final SimulationDatasetId simulationDatasetId) {
    return lookup(planId);
  }

  public boolean isEqualTo(final InMemoryResultsCellRepository other) {
    return this.cells.equals(other.cells);
  }

  /** @deprecated Use {@link #isEqualTo(InMemoryResultsCellRepository)}. */
  @Deprecated
  @Override
  public boolean equals(final Object other) {
    if (!(other instanceof InMemoryResultsCellRepository o)) return false;
    return this.isEqualTo(o);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.cells);
  }

  @Override
  public String toString() {
    return this.cells.toString();
  }

  public static final class InMemoryCell implements ResultsProtocol.OwnerRole {
    private volatile boolean canceled = false;
    private volatile ResultsProtocol.State state = new ResultsProtocol.State.Incomplete(0);
    public final PlanId planId;
    public final long planRevision;

    public InMemoryCell(final PlanId planId, final long planRevision) {
      this.planId = planId;
      this.planRevision = planRevision;
    }

    @Override
    public ResultsProtocol.State get() {
      return this.state;
    }

    @Override
    public void cancel() {
      this.canceled = true;
    }

    @Override
    public boolean isCanceled() {
      return this.canceled;
    }

    @Override
    public void succeedWith(final SimulationResults results) {
      if (!(this.state instanceof ResultsProtocol.State.Incomplete)) {
        throw new IllegalStateException("Cannot transition to success state from state %s".formatted(
            this.state.getClass().getCanonicalName()));
      }

      this.state = new ResultsProtocol.State.Success(0, new InMemorySimulationResultsHandle(results));
    }

    @Override
    public void failWith(final SimulationFailure reason) {
      if (!(this.state instanceof ResultsProtocol.State.Incomplete)) {
        throw new IllegalStateException("Cannot transition to failed state from state %s".formatted(
            this.state.getClass().getCanonicalName()));
      }

      this.state = new ResultsProtocol.State.Failed(0, reason);
    }

    @Override
    public void reportIncompleteResults(final SimulationResults results) {
      this.state = new ResultsProtocol.State.Incomplete(0);
    }

    @Override
    public void reportSimulationExtent(final Duration extent) {
      System.out.println("Simulation extent: " + extent);
    }

    public boolean isEqualTo(final InMemoryCell other) {
      if (this.canceled != other.canceled) return false;
      return Objects.equals(this.state, other.state);
    }

    @Deprecated
    @Override
    public boolean equals(final Object other) {
      if (!(other instanceof InMemoryCell o)) return false;
      return this.isEqualTo(o);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.canceled, this.state);
    }
  }

  public static class InMemorySimulationResultsHandle implements SimulationResultsHandle {

    private final SimulationResults simulationResults;

    public InMemorySimulationResultsHandle(final SimulationResults simulationResults) {
      this.simulationResults = simulationResults;
    }

    @Override
    public SimulationDatasetId getSimulationDatasetId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SimulationResults getSimulationResults() {
      return this.simulationResults;
    }

    @Override
    public ProfileSet getProfiles(final List<String> profileNames) {
      final var realProfiles = new HashMap<String, ResourceProfile<RealDynamics>>();
      final var discreteProfiles = new HashMap<String, ResourceProfile<SerializedValue>>();
      for (final var profileName : profileNames) {
        if (this.simulationResults.realProfiles.containsKey(profileName)) {
          realProfiles.put(profileName, this.simulationResults.realProfiles.get(profileName));
        } else if (this.simulationResults.discreteProfiles.containsKey(profileName)) {
          discreteProfiles.put(profileName, this.simulationResults.discreteProfiles.get(profileName));
        }
      }
      return ProfileSet.of(realProfiles, discreteProfiles);
    }

    @Override
    public Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities() {
      return this.simulationResults.simulatedActivities;
    }

    @Override
    public Instant startTime() {
      return this.simulationResults.startTime;
    }

    @Override
    public Duration duration() {
      return this.simulationResults.duration;
    }
  }
}

package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.driver.SimulationException;
import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol.State;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationResultsHandle;
import gov.nasa.jpl.aerie.merlin.server.remotes.ResultsCellRepository;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PostgresResultsCellRepository implements ResultsCellRepository {
  private static final Logger logger = LoggerFactory.getLogger(PostgresResultsCellRepository.class);

  private final DataSource dataSource;

  public PostgresResultsCellRepository(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public ResultsProtocol.OwnerRole allocate(final PlanId planId, final String requestedBy) {
    try (final var connection = this.dataSource.getConnection()) {
      final SimulationRecord simulation = getSimulation(connection, planId);
      final SimulationTemplateRecord template;
      final Timestamp startTime = simulation.simulationStartTime();
      final Timestamp endTime = simulation.simulationEndTime();
      final var arguments = new HashMap<String, SerializedValue>();

      if (simulation.simulationTemplateId().isPresent()) {
          try (final var getSimulationTemplate = new GetSimulationTemplateAction(connection)) {
            final var templateOptional = getSimulationTemplate.get(simulation.simulationTemplateId().get());
            if (templateOptional.isEmpty()) {
              throw new RuntimeException("TemplateRecord should not be empty");
            }
            template = templateOptional.get();
            arguments.putAll(template.arguments());
          }
      }
      if (startTime == null || endTime == null) {
        throw new RuntimeException("Simulation bounds are not fully defined. Unable to simulate.");
      }

      arguments.putAll(simulation.arguments());

      final var dataset = createSimulationDataset(
          connection,
          simulation,
          startTime,
          endTime,
          arguments,
          requestedBy);

      return new PostgresResultsCell(
          this.dataSource,
          simulation,
          dataset.datasetId());
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to allocation simulation cell", ex);
    }
  }

  /**
   * Forcibly allocate a simulation by updating the Simulation Configuration's revision
   */
  @Override
  public ResultsProtocol.OwnerRole forceAllocate(PlanId planId, String requestedBy) {
    try (final var connection = this.dataSource.getConnection();
         final var updateSimConfig = new UpdateSimulationConfigurationRevisionAction(connection)) {
      updateSimConfig.apply(planId.id());
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to allocate simulation cell", ex);
    }
    return allocate(planId, requestedBy);
  }

  /**
   * Claim a simulation
   *
   * <p>
   * For the case where the return value is empty, the simulation is already claimed. All other exceptions are
   * unexpected and result in an {@link Error} being raised.
   * </p>
   *
   * @param planId a plan identifier
   * @param datasetId the identifier of a dataset record
   * @return cell (handle) {@link ResultsProtocol.OwnerRole} to the claimed simulation wrapped in {@link Optional}
   */
  @Override
  public Optional<ResultsProtocol.OwnerRole> claim(final PlanId planId, final Long datasetId) {
    try (final var connection = this.dataSource.getConnection()) {
      claimSimulationDataset(connection, datasetId);
      logger.info("Claimed simulation with dataset id {}", datasetId);

      final var simulation = getSimulation(connection, planId);

      return Optional.of(new PostgresResultsCell(
          this.dataSource,
          simulation,
          datasetId));
    } catch(UnclaimableSimulationException ex) {
      return Optional.empty();
    } catch(final SQLException | DatabaseException ex) {
      throw new Error(ex.getMessage());
    }
  }

  @Override
  public Optional<ResultsProtocol.ReaderRole> lookup(final PlanId planId) {
    try (final var connection = this.dataSource.getConnection()) {
      final var simulation = getSimulation(connection, planId);
      final var datasetRecord = lookupSimulationDatasetRecord(connection, simulation.id());

      if (datasetRecord.isEmpty()) return Optional.empty();

      final var datasetId = datasetRecord.get().datasetId();
      return Optional.of(new PostgresResultsCell(this.dataSource, simulation, datasetId));
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get simulation", ex);
    }
  }

  @Override
  public Optional<ResultsProtocol.ReaderRole> lookup(final PlanId planId, final SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException {
    try (final var connection = this.dataSource.getConnection()) {
      final var simulation = getSimulation(connection, planId);
      final var datasetRecord = getSimulationDatasetRecordById(connection, simulationDatasetId.id());

      if (datasetRecord.isEmpty()) return Optional.empty();
      // If this check fails, then the specified sim dataset is not a simulation for the specified plan
      if (datasetRecord.get().simulationId() != simulation.id()) {
        throw new SimulationDatasetMismatchException(
            planId,
            new SimulationDatasetId(datasetRecord.get().simulationDatasetId()));
      }

      final var datasetId = datasetRecord.get().datasetId();
      return Optional.of(new PostgresResultsCell(this.dataSource, simulation, datasetId));
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get simulation", ex);
    }
  }

  /* Database accessors */
  private static SimulationRecord getSimulation(
      final Connection connection,
      final PlanId planId
  ) throws SQLException
  {
    try (final var getSimulationAction = new GetSimulationAction(connection)) {
      return getSimulationAction.get(planId.id());
    }
  }

  private static Optional<SimulationDatasetRecord> lookupSimulationDatasetRecord(
      final Connection connection,
      final long simulationId
  ) throws SQLException
  {
    try (final var lookupSimulationDatasetAction = new LookupSimulationDatasetAction(connection)) {
      return lookupSimulationDatasetAction.get(simulationId);
    }
  }

  private static Optional<SimulationDatasetRecord> getSimulationDatasetRecord(
      final Connection connection,
      final long datasetId
  ) throws SQLException
  {
    try (final var getSimulationDatasetAction = new GetSimulationDatasetAction(connection)) {
      return getSimulationDatasetAction.get(datasetId);
    }
  }

  private static Optional<SimulationDatasetRecord> getSimulationDatasetRecordById(
      final Connection connection,
      final long simulationDatasetId
  ) throws SQLException
  {
    try (final var lookupSimulationDatasetAction = new GetSimulationDatasetByIdAction(connection)) {
      return lookupSimulationDatasetAction.get(simulationDatasetId);
    }
  }

  private static SimulationDatasetRecord createSimulationDataset(
      final Connection connection,
      final SimulationRecord simulation,
      final Timestamp simulationStart,
      final Timestamp simulationEnd,
      final Map<String, SerializedValue> arguments,
      final String requestedBy
  ) throws SQLException
  {
    try (final var createSimulationDatasetAction = new CreateSimulationDatasetAction(connection)) {
      return createSimulationDatasetAction.apply(
          simulation.id(),
          simulationStart,
          simulationEnd,
          arguments,
          requestedBy);
    }
  }
  /**
   * Claim a simulation dataset, throwing an {@link UnclaimableSimulationException} if the dataset is already claimed.
   *
   * <p>
   *   The method can be unsuccessful in claiming a simulation in two ways. The first is that an
   *   {@link UnclaimableSimulationException} is thrown if the simulation is already claimed. The second is that
   *   there has been an SQL error resulting in {@link DatabaseException}.
   * </p>
   *
   * @param  connection an SQL database connection
   * @param  datasetId the identifier of a dataset record
   */
  private static void claimSimulationDataset(
      final Connection connection,
      final long datasetId
  ) throws SQLException, UnclaimableSimulationException
  {
    try (final var claimSimulationAction = new ClaimSimulationAction(connection)) {
        claimSimulationAction.apply(datasetId);
    }
  }

  private static void cancelSimulation(
      final Connection connection,
      final long datasetId
  ) throws SQLException, NoSuchSimulationDatasetException
  {
    try (final var cancelSimulationAction = new CancelSimulationAction(connection)) {
      cancelSimulationAction.apply(datasetId);
    }
  }

  private static void failSimulation(
      final Connection connection,
      final long datasetId,
      final SimulationFailure reason
  ) throws SQLException, NoSuchSimulationDatasetException
  {
    try (final var setSimulationStateAction = new SetSimulationStateAction(connection)) {
      setSimulationStateAction.apply(datasetId, SimulationStateRecord.failed(reason));
    }
  }

  private static void deleteSimulationExtent(final Connection connection, final long datasetId)
  throws SQLException, NoSuchSimulationDatasetException
  {
    try (final var deleteSimulationExtent = new DeleteSimulationExtentAction(connection)) {
      deleteSimulationExtent.apply(datasetId);
    }
  }

  private static void reportSimulationExtent(
      final Connection connection,
      final long datasetId,
      final Duration extent
  ) throws SQLException, NoSuchSimulationDatasetException
  {
    try (final var updateSimulationExtentAction = new UpdateSimulationExtentAction(connection)) {
      updateSimulationExtentAction.apply(datasetId, extent);
    }
  }

  private static List<Triple<Integer, String, ValueSchema>> getSimulationTopics(Connection connection, long datasetId)
  throws SQLException
  {
    try (final var getSimulationTopicsAction = new GetSimulationTopicsAction(connection)) {
      return getSimulationTopicsAction.get(datasetId);
    }
  }

  private static SortedMap<Duration, List<EventGraph<EventRecord>>>
  getSimulationEvents(
      final Connection connection,
      final long datasetId
  ) throws SQLException
  {
    try (final var getSimulationEventsAction = new GetSimulationEventsAction(connection)) {
      return getSimulationEventsAction.get(datasetId);
    }
  }

  private static Pair<Map<ActivityInstanceId, ActivityInstance>, Map<ActivityInstanceId, UnfinishedActivity>> getActivities(
      final Connection connection,
      final long datasetId,
      final Timestamp startTime
  ) throws SQLException
  {
    try (final var getActivitiesAction = new GetSpanRecords(connection)) {
      final var activityRecords = getActivitiesAction.get(datasetId, startTime);

      // Remap all activity IDs to reflect lifted directive IDs
      final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>();
      final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>();
      for (final var entry : activityRecords.entrySet()) {
        final var pgId = entry.getKey();
        final var record = entry.getValue();
        final var activityInstanceId = new ActivityInstanceId(pgId);

        // Only records with duration and computed attributes represent simulated activities
        if (record.duration().isPresent() && record.attributes().computedAttributes().isPresent()) {
          simulatedActivities.put(activityInstanceId, new ActivityInstance(
              record.type(),
              record.attributes().arguments(),
              record.start(),
              record.duration().get(),
              record.parentId().map(ActivityInstanceId::new).orElse(null),
              record.childIds().stream().map(ActivityInstanceId::new).collect(Collectors.toList()),
              record.attributes().directiveId().map(ActivityDirectiveId::new),
              record.attributes().computedAttributes().get()
          ));
        } else {
          unfinishedActivities.put(activityInstanceId, new UnfinishedActivity(
              record.type(),
              record.attributes().arguments(),
              record.start(),
              record.parentId().map(ActivityInstanceId::new).orElse(null),
              record.childIds().stream().map(ActivityInstanceId::new).collect(Collectors.toList()),
              record.attributes().directiveId().map(ActivityDirectiveId::new)
          ));
        }
      }

      return Pair.of(simulatedActivities, unfinishedActivities);
    }
  }

  private static void postSimulationResults(
      final Connection connection,
      final long datasetId,
      final SimulationResults results,
      final SimulationStateRecord state
  ) throws SQLException, NoSuchSimulationDatasetException
  {
    final var simulationStart = new Timestamp(results.startTime);
    postActivities(connection, datasetId, results.simulatedActivities, results.unfinishedActivities, simulationStart);
    insertSimulationTopics(connection, datasetId, results.topics);
    insertSimulationEvents(connection, datasetId, results.events, simulationStart);

    try (final var setSimulationStateAction = new SetSimulationStateAction(connection)) {
      setSimulationStateAction.apply(datasetId, state);
    }
  }

  private static void insertSimulationTopics(
      Connection connection,
      long datasetId,
      final List<Triple<Integer, String, ValueSchema>> topics) throws SQLException
  {
    try (
        final var insertSimulationTopicsAction = new InsertSimulationTopicsAction(connection)
    ) {
      insertSimulationTopicsAction.apply(datasetId, topics);
    }
  }

  private static void insertSimulationEvents(
      Connection connection,
      long datasetId,
      Map<Duration, List<EventGraph<EventRecord>>> events,
      Timestamp simulationStart) throws SQLException
  {
    try (
        final var insertSimulationEventsAction = new InsertSimulationEventsAction(connection)
    ) {
        insertSimulationEventsAction.apply(datasetId, events, simulationStart);
    }
  }

  private static void postActivities(
      final Connection connection,
      final long datasetId,
      final Map<ActivityInstanceId, ActivityInstance> simulatedActivities,
      final Map<ActivityInstanceId, UnfinishedActivity> unfinishedActivities,
      final Timestamp simulationStart
  ) throws SQLException {
    try (final var postActivitiesAction = new PostSpansAction(connection)) {
      final var simulatedActivityRecords = simulatedActivities.entrySet().stream()
          .collect(Collectors.toMap(
              e -> e.getKey().id(),
              e -> simulatedActivityToRecord(e.getValue())));

      final var allActivityRecords = unfinishedActivities.entrySet().stream()
          .collect(Collectors.toMap(
              e -> e.getKey().id(),
              e -> unfinishedActivityToRecord(e.getValue())));
      allActivityRecords.putAll(simulatedActivityRecords);

      // Sorts the map by SpanRecord parent ID to ensure foreign key constraints are met.
      // Entries with null parent IDs are placed first to avoid foreign key violations
      // for the "span_has_parent_span" constraint.
      final var sortedAllActivityRecords = topoSort(allActivityRecords, $ -> $.parentId().stream().toList());

      postActivitiesAction.apply(
          datasetId,
          sortedAllActivityRecords,
          simulationStart);
    }
  }

  /**
   * Take an unsorted map and produce a sorted LinkedHashMap where nodes always
   * come after their dependencies.
   * @param nodes a map from keys to values - the keys are used to define dependencies
   * @param dependencies - for a given value, what are the keys of its dependencies?
   * @return a sorted LinkedHashMap where nodes always come after their dependencies
   * @throws IllegalArgumentException if a cycle is found
   */
  private static <K, V> LinkedHashMap<K, V> topoSort(Map<K, V> nodes, Function<V, List<K>> dependencies) {
    final var worklist = new ArrayList<>(nodes.entrySet());
    final var sortedMap = new LinkedHashMap<K, V>();
    while (!worklist.isEmpty()) {
      var madeProgress = false;
      for (int i = worklist.size() - 1; i >= 0; i--) {
        final var entry = worklist.get(i);
        // A node is ready to be added to the output if all of its dependencies are already in the output
        if (dependencies.apply(entry.getValue()).stream().allMatch(sortedMap::containsKey)) {
          sortedMap.put(entry.getKey(), entry.getValue());
          worklist.remove(i);
          madeProgress = true;
        }
      }
      // If no nodes were added to the output in this round, there must be a cycle in the remaining nodes
      if (!madeProgress) {
        throw new IllegalArgumentException("Cycle detected in input to topoSort:" + worklist);
      }
    }
    return sortedMap;
  }

  private static SpanRecord simulatedActivityToRecord(final ActivityInstance activity) {
    return new SpanRecord(
        activity.type(),
        activity.start(),
        Optional.of(activity.duration()),
        Optional.ofNullable(activity.parentId()).map(ActivityInstanceId::id),
        activity.childIds().stream().map(ActivityInstanceId::id).collect(Collectors.toList()),
        new ActivityAttributesRecord(
          activity.directiveId().map(ActivityDirectiveId::id),
          activity.arguments(),
          Optional.of(activity.computedAttributes())));
  }

  private static SpanRecord unfinishedActivityToRecord(final UnfinishedActivity activity) {
    return new SpanRecord(
        activity.type(),
        activity.start(),
        Optional.empty(),
        Optional.ofNullable(activity.parentId()).map(ActivityInstanceId::id),
        activity.childIds().stream().map(ActivityInstanceId::id).collect(Collectors.toList()),
        new ActivityAttributesRecord(
            activity.directiveId().map(ActivityDirectiveId::id),
            activity.arguments(),
            Optional.empty()));
  }

  public static final class PostgresResultsCell implements ResultsProtocol.OwnerRole {
    private final DataSource dataSource;
    private final SimulationRecord simulation;
    private final long datasetId;

    public PostgresResultsCell(
        final DataSource dataSource,
        final SimulationRecord simulation,
        final long datasetId
    ) {
      this.dataSource = dataSource;
      this.simulation = simulation;
      this.datasetId = datasetId;
    }

    @Override
    public State get() {
      try (final var connection = dataSource.getConnection()) {
        Optional<State> result;
        final var record$ = getSimulationDatasetRecord(
            connection,
            datasetId);
        if (record$.isEmpty()) {
          result = Optional.empty();
        } else {
          final var record = record$.get();
          result = Optional.of(
              switch (record.state().status()) {
                case PENDING -> new State.Pending(record.simulationDatasetId());
                case INCOMPLETE -> new State.Incomplete(record.simulationDatasetId());
                case FAILED -> new State.Failed(record.simulationDatasetId(), record.state().reason()
                    .orElseThrow(() -> new Error("Unexpected state: %s request state has no failure message".formatted(record.state().status()))));
                case SUCCESS -> new State.Success(
                    record.simulationDatasetId(),
                    new PostgresSimulationResultsHandle(dataSource, record));
              });
        }

        return result.orElseThrow(() -> new Error("Dataset corrupted"));
      } catch (final SQLException ex) {
        throw new DatabaseException("Failed to get dataset", ex);
      }
    }

    @Override
    public void cancel() {
      try (final var connection = dataSource.getConnection()) {
        cancelSimulation(connection, datasetId);
      } catch(final SQLException ex) {
        throw new DatabaseException("Failed to cancel simulation", ex);
      } catch (final NoSuchSimulationDatasetException ex) {
        // A cell should only be created for a valid, existing dataset
        // A dataset should only be deleted by its cell
        throw new Error("Cell references nonexistent simulation dataset");
      }
    }

    @Override
    public boolean isCanceled() {
      try (final var connection = dataSource.getConnection()) {
        return lookupSimulationDatasetRecord(
            connection,
            simulation.id())
         .map(SimulationDatasetRecord::canceled)
         .orElseThrow(() -> new Error("Dataset corrupted"));
      } catch (final SQLException ex) {
        throw new DatabaseException("Failed to check cancellation status", ex);
      }
    }

    @Override
    public void succeedWith(final SimulationResults results) {
      try (final var connection = dataSource.getConnection();
           final var transactionContext = new TransactionContext(connection)) {
        postSimulationResults(connection, datasetId, results, SimulationStateRecord.success());
        deleteSimulationExtent(connection, datasetId);
        transactionContext.commit();
        logger.info("%s set simulation status to success".formatted(Duration.microseconds(System.nanoTime() / 1000)));
      } catch (final SQLException ex) {
        throw new DatabaseException("Failed to store simulation results", ex);
      } catch (final NoSuchSimulationDatasetException ex) {
        // A cell should only be created for a valid, existing dataset
        // A dataset should only be deleted by its cell
        throw new Error("Cell references nonexistent simulation dataset");
      }
    }

    @Override
    public void failWith(final SimulationFailure reason) {
      try (final var connection = dataSource.getConnection();
           final var transactionContext = new TransactionContext(connection)) {
        failSimulation(connection, datasetId, reason);
        deleteSimulationExtent(connection, datasetId);
        transactionContext.commit();
        logger.info("%s set simulation status to failed".formatted(Duration.microseconds(System.nanoTime() / 1000)));
      } catch (final SQLException ex) {
        throw new DatabaseException("Failed to update simulation state to failure", ex);
      } catch (final NoSuchSimulationDatasetException ex) {
        // A cell should only be created for a valid, existing dataset
        // A dataset should only be deleted by its cell
        throw new Error("Cell references nonexistent simulation dataset");
      }
    }

    @Override
    public void reportIncompleteResults(final SimulationResults results) {
      try (final var connection = dataSource.getConnection();
           final var transactionContext = new TransactionContext(connection)) {
        final var reason = new SimulationFailure.Builder()
            .type("SIMULATION_CANCELED")
            .data(Json.createObjectBuilder()
                    .add("elapsedTime", SimulationException.formatDuration(results.duration))
                    .add("utcTimeDoy", SimulationException.formatInstant(Duration.addToInstant(results.startTime, results.duration)))
                    .build())
            .message("Simulation run was canceled")
            .build();
        postSimulationResults(connection, datasetId, results, SimulationStateRecord.incomplete(reason));
        deleteSimulationExtent(connection, datasetId);
        transactionContext.commit();
      } catch (final SQLException ex) {
        throw new DatabaseException("Failed to store simulation results", ex);
      } catch (final NoSuchSimulationDatasetException ex) {
        // A cell should only be created for a valid, existing dataset
        // A dataset should only be deleted by its cell
        throw new Error("Cell references nonexistent simulation dataset");
      }
    }

    @Override
    public void reportSimulationExtent(final Duration extent) {
      try (final var connection = dataSource.getConnection()) {
        PostgresResultsCellRepository.reportSimulationExtent(connection, datasetId, extent);
      } catch (SQLException ex) {
        throw new DatabaseException("Failed to update simulation extent", ex);
      } catch (NoSuchSimulationDatasetException e) {
        // A cell should only be created for a valid, existing dataset
        // A dataset should only be deleted by its cell
        throw new Error("Cell references nonexistent simulation dataset");
      }
    }
  }

  public static class PostgresSimulationResultsHandle implements SimulationResultsHandle {

    SimulationDatasetRecord record;
    DataSource dataSource;

    public PostgresSimulationResultsHandle(DataSource dataSource, SimulationDatasetRecord record) {
      this.dataSource = dataSource;
      this.record = record;
    }

    @Override
    public SimulationDatasetId getSimulationDatasetId() {
      return new SimulationDatasetId(this.record.simulationDatasetId());
    }

    @Override
    public SimulationResults getSimulationResults() {
      try (final var connection = this.dataSource.getConnection()) {
        final var startTimestamp = record.simulationStartTime();
        final var simulationStart = startTimestamp.toInstant();
        final var simulationDuration = Duration.of(
            startTimestamp.microsUntil(record.simulationEndTime()),
            Duration.MICROSECONDS);

        final var profiles = ProfileRepository.getProfiles(connection, record.datasetId());
        final var activities = getActivities(connection, record.datasetId(), startTimestamp);
        final var topics = getSimulationTopics(connection, record.datasetId());
        final var events = getSimulationEvents(connection, record.datasetId());

        return new SimulationResults(
            ProfileSet.unwrapOptional(profiles.realProfiles()),
            ProfileSet.unwrapOptional(profiles.discreteProfiles()),
            activities.getLeft(),
            activities.getRight(),
            simulationStart,
            simulationDuration,
            topics,
            events
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public ProfileSet getProfiles(final List<String> profileNames) {
      try (final var connection = this.dataSource.getConnection()) {
        return ProfileRepository.getProfiles(connection, record.datasetId(), profileNames);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities() {
      try (final var connection = this.dataSource.getConnection()) {
        final var activities = getActivities(
            connection,
            record.datasetId(),
            record.simulationStartTime());
        return activities.getLeft();
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Instant startTime() {
      return record.simulationStartTime().toInstant();
    }

    @Override
    public Duration duration() {
      return Duration.of(
          record.simulationStartTime().microsUntil(record.simulationEndTime()),
          Duration.MICROSECONDS);
    }
  }
}

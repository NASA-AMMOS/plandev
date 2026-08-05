package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.InvalidSimulationDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonEntityException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PostgresPlanRepository implements PlanRepository {
  private final DataSource dataSource;
  private final Path rootFilePath;

  public PostgresPlanRepository(final DataSource dataSource, final Path rootFilePath) {
    this.dataSource = dataSource;
    this.rootFilePath = rootFilePath;
  }

  // GetAllPlans is exclusively used in tests currently and none of its usages are for simulation
  // Therefore, this is implicitly GetAllPlans(ForValidation)
  @Override
  public Map<PlanId, Plan> getAllPlans() {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getAllPlansAction = new GetAllPlansAction(connection)) {
        final var planRecords = getAllPlansAction.get();
        final var plans = new HashMap<PlanId, Plan>(planRecords.size());

        for (final var record : planRecords) {
          try {
            final var planId = new PlanId(record.id());
            final var activities = getPlanActivities(connection, planId);

            plans.put(planId, new Plan(
                record.name(),
                new MissionModelId(record.missionModelId()),
                record.startTime(),
                record.endTime(),
                activities
            ));
          } catch (final NoSuchPlanException ex) {
            // If a plan was removed between getting its record and getting its activities, then the plan
            // no longer exists, so it's okay to swallow the exception and continue
            System.err.println("Plan was removed while retrieving all plans. Continuing without removed plan.");
          }
        }

        return plans;
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get all plans", ex);
    }
  }

  @Override
  public Plan getPlanForSimulation(final PlanId planId) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      final var planRecord = getPlanRecord(connection, planId);
      final var simulationRecord = getSimRecord(connection, planId.id());
      final Optional<SimulationTemplateRecord> templateRecord;
      if (simulationRecord.simulationTemplateId().isPresent()) {
        templateRecord = getTemplate(connection, simulationRecord.simulationTemplateId().get());
      } else {
        templateRecord = Optional.empty();
      }

      final var activities = getPlanActivities(connection, planId);
      final var arguments = getSimulationArguments(simulationRecord, templateRecord);
      final var simStartTime = simulationRecord.simulationStartTime();
      final var simEndTime = simulationRecord.simulationEndTime();

      return new Plan(
          planRecord.name(),
          new MissionModelId(planRecord.missionModelId()),
          planRecord.startTime(),
          planRecord.endTime(),
          activities,
          arguments,
          simStartTime,
          simEndTime
      );
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get plan", ex);
    }
  }

  @Override
  public Plan getPlanForValidation(final PlanId planId) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      final var planRecord = getPlanRecord(connection, planId);
      final var activities = getPlanActivities(connection, planId);

      return new Plan(
          planRecord.name(),
          new MissionModelId(planRecord.missionModelId()),
          planRecord.startTime(),
          planRecord.endTime(),
          activities
      );
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get plan", ex);
    }
  }


  private SimulationRecord getSimRecord(final Connection connection, final long planId) throws SQLException {
    try (final var getSimulationAction = new GetSimulationAction(connection)) {
      return getSimulationAction.get(planId);
    } catch (SQLException ex) {
      throw new DatabaseException("Failed to get simulation configuration", ex);
    }
  }

  private Optional<SimulationTemplateRecord> getTemplate(final Connection connection, final long templateID) {
    try (final var getSimulationTemplateAction = new GetSimulationTemplateAction(connection)) {
      return getSimulationTemplateAction.get(templateID);
    } catch (SQLException ex) {
      throw new DatabaseException("Failed to get template", ex);
    }
  }

  private Map<String, SerializedValue> getSimulationArguments(final SimulationRecord simulationRecord, final Optional<SimulationTemplateRecord> templateRecord)
  {
    final var arguments = new HashMap<String, SerializedValue>();
    final var templateId$ = simulationRecord.simulationTemplateId();

    // Apply template arguments followed by simulation arguments.
    // Overwriting of template arguments with sim. arguments is intentional here,
    // and the resulting set of arguments is assumed to be complete
    if (templateId$.isPresent()) {
      templateRecord.ifPresentOrElse(
          simTemplateRecord -> arguments.putAll(simTemplateRecord.arguments()),
          () -> {
            throw new RuntimeException("TemplateRecord should not be empty");
          });
    }

    arguments.putAll(simulationRecord.arguments());
    return arguments;
  }

  @Override
  public long getPlanRevision(final PlanId planId) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      return getPlanRecord(connection, planId).revision();
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get plan revision", ex);
    }
  }

  @Override
  public PostgresPlanRevisionData getPlanRevisionData(final PlanId planId) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getPlanRevisionDataAction = new GetPlanRevisionDataAction(connection)) {
        return getPlanRevisionDataAction
            .get(planId.id())
            .orElseThrow(() -> new NoSuchPlanException(planId));
      }
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to get plan revision data", ex);
    }
  }

  @Override
  public List<ConstraintRecord> getPlanConstraints(final PlanId planId) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getPlanConstraintsAction = new GetPlanConstraintsAction(connection, rootFilePath)) {
        return getPlanConstraintsAction
            .get(planId.id())
            .orElseThrow(() -> new NoSuchPlanException(planId));
      }
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to retrieve constraints for plan with id `%s`".formatted(planId), ex);
    }
  }

  @Override
  public long addExternalDataset(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final Timestamp datasetStart,
      final ProfileSet profileSet
  ) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      final var plan = getPlanRecord(connection, planId);
      final var planDataset = createPlanDataset(
          connection,
          planId,
          simulationDatasetId,
          plan.startTime(),
          datasetStart);
      ProfileRepository.postResourceProfiles(
          connection,
          planDataset.datasetId(),
          profileSet
      );

      return planDataset.datasetId();
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to add external dataset to plan with id `%s`".formatted(planId), ex);
    }
  }

  @Override
  public long uploadSimulationDataset(
      final PlanId planId,
      final SimulationResults simulationResults,
      final String requestedBy
  ) throws NoSuchPlanException, InvalidSimulationDatasetException {
    try (final var connection = this.dataSource.getConnection();
         final var transactionContext = new TransactionContext(connection)) {
      final var planRecord = getPlanRecord(connection, planId);

      // Validate that all activity types in the dataset exist in the plan's mission model
      validateActivityTypes(connection, planRecord.missionModelId(), simulationResults);

      final var simulation = getSimulation(connection, planId);
      final var simulationStart = new Timestamp(simulationResults.startTime);
      final var simulationEnd = simulationStart.plusMicros(simulationResults.duration.in(Duration.MICROSECONDS));

      final var simulationDatasetRecord = createSimulationDataset(
          connection,
          simulation,
          simulationStart,
          simulationEnd,
          simulationResults.simulationArguments,
          requestedBy);

      final var datasetId = simulationDatasetRecord.datasetId();

      final var profileSet = ProfileSet.of(simulationResults.realProfiles, simulationResults.discreteProfiles);
      ProfileRepository.postResourceProfiles(connection, datasetId, profileSet);

      PostgresResultsCellRepository.postActivities(
          connection,
          datasetId,
          simulationResults.simulatedActivities,
          simulationResults.unfinishedActivities,
          simulationStart);

      PostgresResultsCellRepository.insertSimulationTopics(
          connection,
          datasetId,
          simulationResults.topics);

      PostgresResultsCellRepository.insertSimulationEvents(
          connection,
          datasetId,
          simulationResults.events,
          simulationStart);

      try (final var setSimulationStateAction = new SetSimulationStateAction(connection)) {
        setSimulationStateAction.apply(datasetId, SimulationStateRecord.success());
      }

      transactionContext.commit();
      return simulationDatasetRecord.simulationDatasetId();
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to upload simulation dataset for plan with id `%s`".formatted(planId), ex);
    } catch (final NoSuchSimulationDatasetException ex) {
      throw new Error("Simulation dataset was created but then not found", ex);
    }
  }

  @Override
  public SimulationResults downloadSimulationDataset(
      final PlanId planId,
      final long simulationDatasetId
  ) throws NoSuchPlanException {
    try (final var connection = this.dataSource.getConnection()) {
      // Verify the plan exists
      getPlanRecord(connection, planId);

      // Fetch the simulation dataset record
      final SimulationDatasetRecord record;
      try (final var getAction = new GetSimulationDatasetByIdAction(connection)) {
        record = getAction.get(simulationDatasetId)
            .orElseThrow(() -> new RuntimeException(
                "No simulation dataset with id `%s` exists".formatted(simulationDatasetId)));
      }

      final var startTimestamp = record.simulationStartTime();
      final var simulationStart = startTimestamp.toInstant();
      final var simulationDuration = Duration.of(
          startTimestamp.microsUntil(record.simulationEndTime()),
          Duration.MICROSECONDS);

      final var profiles = ProfileRepository.getProfiles(connection, record.datasetId());
      final var activities = PostgresResultsCellRepository.getActivities(connection, record.datasetId(), startTimestamp);
      final var topics = PostgresResultsCellRepository.getSimulationTopics(connection, record.datasetId());
      final var events = PostgresResultsCellRepository.getSimulationEvents(connection, record.datasetId());

      // Fetch simulation arguments from the simulation_dataset row
      final Map<String, SerializedValue> simulationArguments;
      try (final var getArgsStatement = connection.prepareStatement(
          "select arguments from merlin.simulation_dataset where id = ?")) {
        getArgsStatement.setLong(1, simulationDatasetId);
        try (final var rs = getArgsStatement.executeQuery()) {
          if (rs.next()) {
            final var argsJson = rs.getString("arguments");
            if (argsJson != null) {
              simulationArguments = PostgresParsers.simulationArgumentsP
                  .parse(Json.createReader(new java.io.StringReader(argsJson)).readValue())
                  .getSuccessOrThrow(e -> new RuntimeException(
                      "Failed to parse simulation arguments for dataset id `%s`".formatted(simulationDatasetId)));
            } else {
              simulationArguments = Map.of();
            }
          } else {
            simulationArguments = Map.of();
          }
        }
      }

      return new SimulationResults(
          ProfileSet.unwrapOptional(profiles.realProfiles()),
          ProfileSet.unwrapOptional(profiles.discreteProfiles()),
          activities.getLeft(),
          activities.getRight(),
          simulationStart,
          simulationDuration,
          topics,
          events,
          simulationArguments);
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to download simulation dataset with id `%s` for plan `%s`".formatted(simulationDatasetId, planId), ex);
    }
  }

  @Override
  public void extendExternalDataset(
      final DatasetId datasetId,
      final ProfileSet profileSet
  ) throws NoSuchPlanDatasetException {
    try (final var connection = this.dataSource.getConnection()) {
      if (!planDatasetExists(connection, datasetId)) {
        throw new NoSuchPlanDatasetException(datasetId);
      }
      ProfileRepository.appendResourceProfiles(
          connection,
          datasetId.id(),
          profileSet
      );
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to extend external dataset with id `%s`".formatted(datasetId), ex);
    }
  }

  private static boolean planDatasetExists(final Connection connection, final DatasetId datasetId) throws SQLException {
    try (final var getPlanDatasetAction = new CheckPlanDatasetExistsAction(connection)) {
      return getPlanDatasetAction.get(datasetId);
    }
  }

  @Override
  public List<Pair<Duration, ProfileSet>> getExternalDatasets(
      final PlanId planId,
      final SimulationDatasetId simulationDatasetId)
  {
    try (final var connection = this.dataSource.getConnection()) {
      final var planDatasets = ProfileRepository.getPlanDatasetsForPlan(connection, planId, Optional.of(simulationDatasetId));
      final var result = new ArrayList<Pair<Duration, ProfileSet>>();
      for (final var planDataset: planDatasets) {
        result.add(Pair.of(
            planDataset.offsetFromPlanStart(),
            ProfileRepository.getProfiles(connection, planDataset.datasetId())
        ));
      }
      return result;
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to get external datasets for plan with id `%s`".formatted(planId), ex);
    }
  }

  @Override
  public Map<String, List<ExternalEvent>> getExternalEvents(
      final PlanId planId,
      final Instant horizonStart) {
    try (final var connection = this.dataSource.getConnection()) {
        return ExternalEventRepository.getExternalEvents(connection, planId, horizonStart);
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to get external events for plan with id `%s`".formatted(planId), ex);
    } catch (final InvalidJsonEntityException in) {
      throw new RuntimeException(
          ("Failed to get external events for plan with id `%s; "
           + "failed to parse jsonb for external event/source attributes`").formatted(planId), in);
    }
  }

  @Override
  public Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) throws DatabaseException {
    try (final var connection = this.dataSource.getConnection()) {
      final var planDatasets = ProfileRepository.getPlanDatasetsForPlan(connection, planId, simulationDatasetId);
      final var result = new HashMap<String, ValueSchema>();
      for (final var planDataset: planDatasets) {
        final var schemas = ProfileRepository.getProfileSchemas(connection, planDataset.datasetId());
        result.putAll(schemas);
      }
      return result;
    } catch (final SQLException ex) {
      throw new DatabaseException(
          "Failed to get external resource schemas for plan with id `%s`".formatted(planId), ex
      );
    }
  }

  private PlanRecord getPlanRecord(
      final Connection connection,
      final PlanId planId
  ) throws SQLException, NoSuchPlanException {
    try (final var getPlanAction = new GetPlanAction(connection)) {
      return getPlanAction
          .get(planId.id())
          .orElseThrow(() -> new NoSuchPlanException(planId));
    }
  }

  private Map<ActivityDirectiveId, ActivityDirective> getPlanActivities(
      final Connection connection,
      final PlanId planId
  ) throws SQLException, NoSuchPlanException {
    try (
        final var getActivitiesAction = new GetActivityDirectivesAction(connection)
    ) {
      return getActivitiesAction
          .get(planId.id())
          .stream()
          .collect(Collectors.toMap(
              a -> new ActivityDirectiveId(a.id()),
              a -> new ActivityDirective(
                  Duration.of(a.startOffsetInMicros(), Duration.MICROSECONDS),
                  a.type(),
                  a.arguments(),
                  a.anchorId()!=null? new ActivityDirectiveId(a.anchorId()): null,
                  a.anchoredToStart())));
    }
  }

  private static PlanDatasetRecord createPlanDataset(
      final Connection connection,
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final Timestamp planStart,
      final Timestamp datasetStart
  ) throws SQLException {
    try (final var createPlanDatasetAction = new CreatePlanDatasetAction(connection)) {
      return createPlanDatasetAction.apply(planId.id(), simulationDatasetId, planStart, datasetStart);
    }
  }

  private static void validateActivityTypes(
      final Connection connection,
      final long missionModelId,
      final SimulationResults simulationResults
  ) throws SQLException, InvalidSimulationDatasetException {
    final var datasetActivityTypes = new HashSet<String>();
    simulationResults.simulatedActivities.values().forEach(a -> datasetActivityTypes.add(a.type()));
    simulationResults.unfinishedActivities.values().forEach(a -> datasetActivityTypes.add(a.type()));

    if (datasetActivityTypes.isEmpty()) return;

    try (final var getActivityTypesAction = new GetActivityTypesAction(connection)) {
      final var modelActivityTypeNames = getActivityTypesAction.get(missionModelId)
          .stream()
          .map(ActivityType::name)
          .collect(Collectors.toSet());

      final var unknownTypes = datasetActivityTypes.stream()
          .filter(t -> !modelActivityTypeNames.contains(t))
          .sorted()
          .toList();

      if (!unknownTypes.isEmpty()) {
        throw new InvalidSimulationDatasetException(unknownTypes);
      }
    }
  }

  private static SimulationRecord getSimulation(
      final Connection connection,
      final PlanId planId
  ) throws SQLException {
    try (final var getSimulationAction = new GetSimulationAction(connection)) {
      return getSimulationAction.get(planId.id());
    }
  }

  private static SimulationDatasetRecord createSimulationDataset(
      final Connection connection,
      final SimulationRecord simulation,
      final Timestamp simulationStart,
      final Timestamp simulationEnd,
      final Map<String, SerializedValue> arguments,
      final String requestedBy
  ) throws SQLException {
    try (final var createSimulationDatasetAction = new CreateSimulationDatasetAction(connection)) {
      return createSimulationDatasetAction.apply(
          simulation.id(),
          simulationStart,
          simulationEnd,
          arguments,
          requestedBy);
    }
  }
}

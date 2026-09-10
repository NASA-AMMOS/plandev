package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.server.models.ExternalSpan;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.ProfileSet;
import gov.nasa.ammos.plandev.merlin.server.remotes.ExternalSimulationResultsRepository;
import gov.nasa.ammos.plandev.merlin.server.services.ExternalModelException;
import gov.nasa.ammos.plandev.types.Timestamp;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class PostgresExternalSimulationResultsRepository implements ExternalSimulationResultsRepository {
  private final DataSource dataSource;

  public PostgresExternalSimulationResultsRepository(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public long insertExternalSimulationResults(
      final PlanId planId,
      final Optional<Long> simulationId,
      final Timestamp simulationStart,
      final Duration simulationDuration,
      final ProfileSet profileSet,
      final List<ExternalSpan> spans,
      final String requestedBy
  ) {
    // SINGLE TRANSACTION: TransactionContext sets autocommit=false so the notify_simulation_workers
    // NOTIFY (queued by the AFTER-insert trigger) is not delivered until commit(), by which point
    // status has been flipped to 'success' and the worker's `where status='pending'` claim can never match.
    try (final var connection = this.dataSource.getConnection();
         final var transactionContext = new TransactionContext(connection)) {

      // 1. Resolve the plan's simulation row. Always read it, even when the caller named a
      //    simulation id: the row is also where the run's CONFIGURATION lives, and a dataset
      //    stamped with no arguments would show the run as having been configured with nothing.
      final SimulationRecord simulation;
      try (final var getSimulation = new GetSimulationAction(connection)) {
        simulation = getSimulation.get(planId.id());
      }
      // A simulation id that is not this plan's would attach the results to another plan's
      // simulation, which no caller can want and nothing downstream would notice.
      if (simulationId.isPresent() && simulationId.get() != simulation.id()) {
        throw new ExternalModelException(
            ExternalModelException.Kind.INGEST_GATE,
            "simulationId %d does not belong to plan %d, whose simulation is %d"
                .formatted(simulationId.get(), planId.id(), simulation.id()));
      }
      final long simId = simulation.id();

      // The same precedence the ordinary simulate path uses (PostgresResultsCellRepository.allocate):
      // the template underneath, the simulation's own arguments on top.
      final var arguments = new HashMap<String, SerializedValue>();
      if (simulation.simulationTemplateId().isPresent()) {
        try (final var getSimulationTemplate = new GetSimulationTemplateAction(connection)) {
          getSimulationTemplate
              .get(simulation.simulationTemplateId().get())
              .ifPresent($ -> arguments.putAll($.arguments()));
        }
      }
      arguments.putAll(simulation.arguments());

      final var simulationEnd = new Timestamp(
          Duration.addToInstant(simulationStart.toInstant(), simulationDuration));

      // 2. Insert simulation_dataset. status defaults to 'pending'; the BEFORE-insert trigger
      //    creates the merlin.dataset row and assigns dataset_id, which we read back here.
      final SimulationDatasetRecord dataset;
      try (final var createSimulationDataset = new CreateSimulationDatasetAction(connection)) {
        dataset = createSimulationDataset.apply(
            simId, simulationStart, simulationEnd, arguments, requestedBy);
      }
      final long datasetId = dataset.datasetId();

      // 3. Spans (parent-before-child ordering for the span_has_parent_span FK).
      try (final var postSpans = new PostSpansAction(connection)) {
        postSpans.apply(datasetId, toSpanRecords(spans, simulationStart), simulationStart);
      }

      // 4. Profiles + segments (joins THIS transaction via the shared connection).
      ProfileRepository.postResourceProfiles(connection, datasetId, profileSet);

      // 5. Flip to success BEFORE commit -> defeats the notify race.
      try (final var setState = new SetSimulationStateAction(connection)) {
        setState.apply(datasetId, SimulationStateRecord.success());
      }

      // 6. Commit -> only now is the NOTIFY delivered, and status is already 'success'.
      transactionContext.commit();
      return dataset.simulationDatasetId();
    } catch (final SQLException ex) {
      throw new DatabaseException("Failed to ingest external simulation results for plan " + planId.id(), ex);
    } catch (final NoSuchSimulationDatasetException ex) {
      // The dataset was created moments earlier in this same transaction, so this should be unreachable.
      throw new RuntimeException("Failed to ingest external simulation results for plan " + planId.id(), ex);
    }
  }

  private static Map<Long, SpanRecord> toSpanRecords(final List<ExternalSpan> spans, final Timestamp simulationStart) {
    final var records = new HashMap<Long, SpanRecord>();
    for (final var span : spans) {
      records.put(span.spanId(), new SpanRecord(
          span.type(),
          Duration.addToInstant(simulationStart.toInstant(), span.startOffset()),
          span.duration(),
          span.parentId(),
          List.of(), // childIds unused by PostSpansAction's INSERT
          new ActivityAttributesRecord(span.directiveId(), span.arguments(), span.computedAttributes())));
    }
    // Parent rows must be inserted before children (non-deferrable span_has_parent_span FK).
    return topoSort(records, $ -> $.parentId().stream().toList());
  }

  // Ordered so that any span whose parent is present in the batch is inserted after its parent.
  private static <K, V> LinkedHashMap<K, V> topoSort(final Map<K, V> nodes, final Function<V, List<K>> dependencies) {
    final var worklist = new ArrayList<>(nodes.entrySet());
    final var sortedMap = new LinkedHashMap<K, V>();
    while (!worklist.isEmpty()) {
      var madeProgress = false;
      for (int i = worklist.size() - 1; i >= 0; i--) {
        final var entry = worklist.get(i);
        final var deps = dependencies.apply(entry.getValue());
        // A dependency not present in this batch (e.g. a pre-existing parent) does not block insertion.
        final var satisfied = deps.stream().allMatch(dep -> sortedMap.containsKey(dep) || !nodes.containsKey(dep));
        if (satisfied) {
          sortedMap.put(entry.getKey(), entry.getValue());
          worklist.remove(i);
          madeProgress = true;
        }
      }
      if (!madeProgress) throw new IllegalArgumentException("Cycle detected in span parent references: " + worklist);
    }
    return sortedMap;
  }
}

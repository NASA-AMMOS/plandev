package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class PostgresSpanStreamer implements AutoCloseable {
  // Buffer information
  private final static int DEFAULT_THRESHOLD = 4096;
  private final int THRESHOLD;

  // Buffers
  private final HashMap<Long, SpanRecord> spansBuffer;
  private final HashMap<Long, List<Pair<Long, SpanRecord>>> heldChildrenBuffer;
  private final HashSet<Long> uploadedSpanIds;

  // Request information
  private final Connection connection;
  private final long datasetId;
  private final Timestamp simulationStart;

  private boolean closed = false;

  public PostgresSpanStreamer(final Connection connection, final long datasetId, final Timestamp simulationStart) {
    this.connection = connection;
    this.datasetId = datasetId;
    this.simulationStart = simulationStart;

    THRESHOLD = DEFAULT_THRESHOLD;
    spansBuffer = HashMap.newHashMap(THRESHOLD);

    heldChildrenBuffer = HashMap.newHashMap(THRESHOLD);
    uploadedSpanIds = new HashSet<>();
  }

  public void accept(long spanId, SpanRecord span) throws SQLException {
    if (closed) throw new IllegalStateException("accept cannot be called on a closed PostgresProfileStreamer");

    // If the span has a parentId, decide whether it needs to be held or can go into the buffer
    if(span.parentId().isPresent()){
      final var pId = span.parentId().get();
      if(uploadedSpanIds.contains(pId)) {
        addToBuffer(spanId, span);
      } else {
        heldChildrenBuffer.putIfAbsent(pId, new ArrayList<>());
        heldChildrenBuffer.get(pId).add(Pair.of(spanId, span));
      }
    } else {
      // Otherwise, add it to the buffer
      addToBuffer(spanId, span);
    }
  }

  private void addToBuffer(long spanId, SpanRecord span) throws SQLException {
    if(spansBuffer.size() == THRESHOLD) {
      postSpans();
    }
    spansBuffer.put(spanId, span);
  }

  private void postSpans() throws SQLException {
    try (final var postSpansAction = new PostSpansAction(connection)) {
      postSpansAction.apply(datasetId, spansBuffer, simulationStart);
    }

    final var newlyUploaded = new HashSet<>(spansBuffer.keySet());
    // Empty the buffer now that it's been posted
    spansBuffer.clear();

    // Log all the posted ids in the HashSet
    uploadedSpanIds.addAll(newlyUploaded);

    // Go through the posted spans and add any children they were waiting on to the buffer to be posted
    for(final var spanId : newlyUploaded)  {
      for(final var span : heldChildrenBuffer.getOrDefault(spanId, List.of())) {
        addToBuffer(span.getKey(), span.getValue());
      }
    }
  }

  @Override
  public void close() throws SQLException {
    if (closed) return;
    closed = true;
    while(!spansBuffer.isEmpty()) {
      postSpans();
    }
  }
}

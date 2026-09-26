package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.types.Timestamp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PostgresSpanStreamer implements AutoCloseable {
  // Buffer information
  private final static int DEFAULT_THRESHOLD = 4096;
  private final int THRESHOLD;

  // Buffers
  private final HashMap<Long, SpanRecord> spansBuffer;

  // Request information
  private final Connection connection;
  private final long datasetId;
  private final Timestamp simulationStart;

  // Parallelism
  private final ExecutorService queryQueue;
  private boolean closed = false;

  public PostgresSpanStreamer(final Connection connection, final long datasetId, final Timestamp simulationStart) {
    this.connection = connection;
    this.datasetId = datasetId;
    this.simulationStart = simulationStart;

    THRESHOLD = DEFAULT_THRESHOLD;
    spansBuffer = HashMap.newHashMap(THRESHOLD);

    this.queryQueue = Executors.newSingleThreadExecutor();
  }

  public void accept(long spanId, SpanRecord span) {
    if (closed) throw new IllegalStateException("accept cannot be called on a closed PostgresProfileStreamer");
    if(spansBuffer.size() == THRESHOLD) {
      postSpans();
    }
    spansBuffer.put(spanId, span);
  }

  private void postSpans() {
    // Make a copy of the map with the current contents, then empty the map
    final var spans = Map.copyOf(spansBuffer);
    spansBuffer.clear();
    // Send the spans off to be posted in another thread
    queryQueue.submit(() -> {
      try (final var postSpansAction = new PostSpansAction(connection)) {
        postSpansAction.apply(datasetId, spans, simulationStart);
      } catch (SQLException e) {
        throw new DatabaseException("Unable to post spans", e);
      }
    });
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    postSpans();
    queryQueue.close();  // This waits for all submitted jobs to complete before returning
  }
}

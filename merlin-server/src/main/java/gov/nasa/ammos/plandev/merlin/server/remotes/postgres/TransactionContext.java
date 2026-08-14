package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * An {@link AutoCloseable} to ensure that incomplete transactions are rolled back.
 *
 * <p>
 * JDBC doesn't specify whether incomplete transactions are rolled back or committed when the connection closes,
 * so we have to do it ourselves.
 * </p>
 */
/*package-local*/ final class TransactionContext implements AutoCloseable {
  private final Connection connection;
  private final boolean oldAutoCommit;

  TransactionContext(final Connection connection) throws SQLException {
    this.connection = connection;
    this.oldAutoCommit = connection.getAutoCommit();

    connection.setAutoCommit(false);
  }

  public void commit() throws SQLException {
    this.connection.commit();
  }

  public void rollback() throws SQLException {
    this.connection.rollback();
  }

  @Override
  public void close() throws SQLException {
    // A rollback immediately after a commit should be a no-op,
    // so we don't need to track whether a transaction is active or not.
    this.connection.rollback();
    this.connection.setAutoCommit(this.oldAutoCommit);
  }
}

package gov.nasa.ammos.plandev.workspace.server.postgres;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore;
import org.intellij.lang.annotations.Language;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PostgresRevisionStore implements WorkspaceRevisionStore {
  private static final @Language("SQL") String nextOrdinalSql = """
    select coalesce(max(ordinal), 0) + 1
    from sequencing.workspace_file_revision
    where workspace_id = ? and file_id = ?;
    """;
  private static final @Language("SQL") String insertSql = """
    insert into sequencing.workspace_file_revision
      (id, workspace_id, file_id, ordinal, display_name, path_at_revision, git_commit_sha, created_by)
    values (?, ?, ?, ?, ?, ?, ?, ?)
    returning *;
    """;
  private static final @Language("SQL") String listSql = """
    select * from sequencing.workspace_file_revision
    where workspace_id = ? and file_id = ?
    order by ordinal;
    """;
  private static final @Language("SQL") String getSql = """
    select * from sequencing.workspace_file_revision
    where workspace_id = ? and id = ?;
    """;

  private final DataSource dataSource;

  public PostgresRevisionStore(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Revision create(
      final int workspaceId,
      final UUID fileId,
      final String pathAtRevision,
      final String commitSha,
      final String createdBy,
      final BeforeCommit beforeCommit) throws Exception
  {
    try (final var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        final long ordinal;
        try (final var statement = connection.prepareStatement(nextOrdinalSql)) {
          statement.setInt(1, workspaceId);
          statement.setObject(2, fileId);
          try (final var res = statement.executeQuery()) {
            res.next();
            ordinal = res.getLong(1);
          }
        }
        final Revision revision;
        // The (workspace_id, file_id, ordinal) unique key rejects a concurrent duplicate if the workspace lock is bypassed
        try (final var statement = connection.prepareStatement(insertSql)) {
          statement.setObject(1, UUID.randomUUID());
          statement.setInt(2, workspaceId);
          statement.setObject(3, fileId);
          statement.setLong(4, ordinal);
          statement.setString(5, WorkspaceRevisionStore.revisionName(ordinal));
          statement.setString(6, pathAtRevision);
          statement.setString(7, commitSha);
          statement.setString(8, createdBy);
          try (final var res = statement.executeQuery()) {
            res.next();
            revision = revision(res);
          }
        }
        beforeCommit.accept(revision);
        connection.commit();
        return revision;
      } catch (Exception e) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  @Override
  public List<Revision> list(final int workspaceId, final UUID fileId) throws SQLException {
    try (final var connection = dataSource.getConnection();
         final var statement = connection.prepareStatement(listSql)) {
      statement.setInt(1, workspaceId);
      statement.setObject(2, fileId);
      try (final var res = statement.executeQuery()) {
        final var out = new ArrayList<Revision>();
        while (res.next()) out.add(revision(res));
        return out;
      }
    }
  }

  @Override
  public Optional<Revision> get(final int workspaceId, final UUID revisionId) throws SQLException {
    try (final var connection = dataSource.getConnection();
         final var statement = connection.prepareStatement(getSql)) {
      statement.setInt(1, workspaceId);
      statement.setObject(2, revisionId);
      try (final var res = statement.executeQuery()) {
        return res.next() ? Optional.of(revision(res)) : Optional.empty();
      }
    }
  }

  private static Revision revision(final ResultSet res) throws SQLException {
    return new Revision(
        res.getObject("id", UUID.class),
        res.getInt("workspace_id"),
        res.getObject("file_id", UUID.class),
        res.getLong("ordinal"),
        res.getString("display_name"),
        res.getString("path_at_revision"),
        res.getString("git_commit_sha"),
        res.getString("created_by"),
        res.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}

package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.versioning.LastEdit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFileSystemServiceTest {
  @Nested
  class EffectiveMetadataTests {
    private static final String CREATED_AT = "2025-01-01T00:00:00Z";
    private static final String SIDECAR_AT = "2025-02-01T00:00:00Z";
    private static final Instant BASELINE_AT = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant EDIT_AT = Instant.parse("2025-04-01T00:00:00Z");

    private static JsonObject sidecarWithStoredEdit() {
      return Json.createObjectBuilder()
                 .add("createdBy", "creator")
                 .add("createdAt", CREATED_AT)
                 .add("lastEditedBy", "sidecar")
                 .add("lastEditedAt", SIDECAR_AT)
                 .build();
    }

    private static void assertLastEdit(final JsonObject metadata, final String by, final String at) {
      assertEquals(by, metadata.getString("lastEditedBy"));
      assertEquals(at, metadata.getString("lastEditedAt"));
    }

    @Test
    void contentEditAfterBaselineOverridesStoredAttribution() {
      final var edit = new LastEdit("git", EDIT_AT, false);

      final var metadata = WorkspaceFileSystemService.effectiveMetadata(sidecarWithStoredEdit(), edit);

      assertLastEdit(metadata, "git", EDIT_AT.toString());
    }

    @Test
    void baselinePreservesCompleteStoredAttribution() {
      final var baseline = new LastEdit("PlanDev", BASELINE_AT, true);

      final var metadata = WorkspaceFileSystemService.effectiveMetadata(sidecarWithStoredEdit(), baseline);

      assertLastEdit(metadata, "sidecar", SIDECAR_AT);
    }

    @Test
    void baselineIsTheFallbackForIncompleteStoredAttribution() {
      final var incomplete = Json.createObjectBuilder(sidecarWithStoredEdit()).remove("lastEditedAt").build();
      final var baseline = new LastEdit("PlanDev", BASELINE_AT, true);

      final var metadata = WorkspaceFileSystemService.effectiveMetadata(incomplete, baseline);

      assertLastEdit(metadata, "PlanDev", BASELINE_AT.toString());
    }

    @Test
    void creationAttributionIsTheFallbackBeforeTheFirstCommit() {
      final var incomplete = Json.createObjectBuilder()
                                 .add("createdBy", "creator")
                                 .add("createdAt", CREATED_AT)
                                 .add("lastEditedBy", "orphaned")
                                 .build();

      final var metadata = WorkspaceFileSystemService.effectiveMetadata(incomplete, null);

      assertLastEdit(metadata, "creator", CREATED_AT);
    }
  }

  @Nested
  class ETagTests {
    private static byte[] bytes(String s) {
      return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sameContentProducesSameToken() {
      assertEquals(
          WorkspaceService.computeETag(bytes("command ABC;")),
          WorkspaceService.computeETag(bytes("command ABC;")));
    }

    @Test
    void differentContentProducesDifferentToken() {
      assertNotEquals(
          WorkspaceService.computeETag(bytes("command ABC;")),
          WorkspaceService.computeETag(bytes("command XYZ;")));
    }

    @Test
    void tokenIsQuotedLowercaseSha256Hex() {
      final var token = WorkspaceService.computeETag(new byte[0]);
      // Strong ETag: a quoted, 64-char lowercase-hex SHA-256 digest.
      assertTrue(token.matches("\"[0-9a-f]{64}\""), "unexpected token format: " + token);
      // SHA-256 of empty input is a well-known constant; pins the algorithm and encoding.
      assertEquals("\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"", token);
    }
  }
}

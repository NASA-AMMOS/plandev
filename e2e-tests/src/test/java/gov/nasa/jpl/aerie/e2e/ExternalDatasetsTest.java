package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.types.ExternalDataset.ProfileInput;
import gov.nasa.jpl.aerie.e2e.types.ExternalDataset.ProfileInput.ProfileSegmentInput;
import gov.nasa.jpl.aerie.e2e.types.ProfileSegment;
import gov.nasa.jpl.aerie.e2e.types.ValueSchema;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.*;

import javax.json.JsonValue;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExternalDatasetsTest {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;
  // Per-Test Data
  private int modelId;
  private int planId;
  private int datasetId;

  // Cross-Test Constants
  private final String datasetOffset = "06:00:00";
  public static final ProfileInput myBooleanProfile =
      new ProfileInput(
          "/my_boolean",
          "discrete",
          ValueSchema.VALUE_SCHEMA_BOOLEAN,
          List.of(
              // 3600000000L is 1hr in microseconds
              new ProfileSegmentInput(3600000000L, JsonValue.FALSE),
              new ProfileSegmentInput(3600000000L, JsonValue.NULL),
              new ProfileSegmentInput(3600000000L, JsonValue.TRUE),
              new ProfileSegmentInput(3600000000L, JsonValue.NULL),
              new ProfileSegmentInput(3600000000L, JsonValue.FALSE)));

  @BeforeAll
  void beforeAll() {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  void afterAll() {
    // Cleanup Requests
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException, InterruptedException {
    // Insert the Mission Model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "External Dataset Tests");
    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - External Dataset Tests",
        "12:00:00",
        "2021-001T00:00:00.000");
    // Insert External Dataset
    datasetId = hasura.insertExternalDataset(
        planId,
        "2021-001T06:00:00.000",
        List.of(myBooleanProfile));
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model, Plan, External Dataset
    hasura.deleteExternalDataset(planId, datasetId);
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Test
  void initialDatasetUploadedCorrectly() throws IOException {
    final var externalDataset = hasura.getExternalDataset(planId, datasetId);

    // Check Properties
    assertTrue(externalDataset.simulationDatasetId().isEmpty());
    assertEquals(datasetOffset, externalDataset.startOffset());
    assertEquals(1, externalDataset.profiles().size());
    assertTrue(externalDataset.profiles().containsKey(myBooleanProfile.name()));

    // Check Profile Segments
    final var profile = externalDataset.profiles().get(myBooleanProfile.name());
    assertEquals(5, profile.size());
    final var expectedSegments = List.of(
        new ProfileSegment("00:00:00", false, JsonValue.FALSE),
        new ProfileSegment("01:00:00", true, JsonValue.NULL),
        new ProfileSegment("02:00:00", false, JsonValue.TRUE),
        new ProfileSegment("03:00:00", true, JsonValue.NULL),
        new ProfileSegment("04:00:00", false, JsonValue.FALSE));
    assertEquals(expectedSegments, profile);
  }

  @Nested
  class ExtendExternalDataset {
    final ProfileInput myBooleanExtension =
        new ProfileInput(
            myBooleanProfile.name(),
            "discrete",
            ValueSchema.VALUE_SCHEMA_BOOLEAN,
            List.of(
                new ProfileSegmentInput(1800000000L, JsonValue.FALSE),
                new ProfileSegmentInput(1800000000L, JsonValue.TRUE)));

    final ProfileInput newProfile =
        new ProfileInput(
            "/new_profile",
            "discrete",
            ValueSchema.VALUE_SCHEMA_BOOLEAN,
            List.of(
                new ProfileSegmentInput(1800000000L, JsonValue.TRUE),
                new ProfileSegmentInput(1800000000L, JsonValue.FALSE),
                new ProfileSegmentInput(1800000000L, JsonValue.NULL),
                new ProfileSegmentInput(1800000000L, JsonValue.TRUE)));

    @Test
    void extendExistingProfile() throws IOException {
      hasura.extendExternalDataset(datasetId, List.of(myBooleanExtension));
      final var externalDataset = hasura.getExternalDataset(planId, datasetId);

      // Check Properties
      assertTrue(externalDataset.simulationDatasetId().isEmpty());
      assertEquals(datasetOffset, externalDataset.startOffset());
      assertEquals(1, externalDataset.profiles().size());
      assertTrue(externalDataset.profiles().containsKey(myBooleanExtension.name()));

      // Check Profile Segments
      final var mbProfile = externalDataset.profiles().get(myBooleanExtension.name());
      assertEquals(7, mbProfile.size());
      final var expectedMBSegments = List.of(
          new ProfileSegment("00:00:00", false, JsonValue.FALSE),
          new ProfileSegment("01:00:00", true, JsonValue.NULL),
          new ProfileSegment("02:00:00", false, JsonValue.TRUE),
          new ProfileSegment("03:00:00", true, JsonValue.NULL),
          new ProfileSegment("04:00:00", false, JsonValue.FALSE),
          new ProfileSegment("05:00:00", false, JsonValue.FALSE),
          new ProfileSegment("05:30:00", false, JsonValue.TRUE));
      assertEquals(expectedMBSegments, mbProfile);
    }

    @Test
    void addNewProfile() throws IOException {
      hasura.extendExternalDataset(datasetId, List.of(newProfile));
      final var externalDataset = hasura.getExternalDataset(planId, datasetId);

      // Check Properties
      assertTrue(externalDataset.simulationDatasetId().isEmpty());
      assertEquals(datasetOffset, externalDataset.startOffset());
      assertEquals(2, externalDataset.profiles().size());
      assertTrue(externalDataset.profiles().containsKey(myBooleanExtension.name()));
      assertTrue(externalDataset.profiles().containsKey(newProfile.name()));

      // Check Profile Segments
      final var mbProfile = externalDataset.profiles().get(myBooleanExtension.name());
      assertEquals(5, mbProfile.size());
      final var expectedMBSegments = List.of(
          new ProfileSegment("00:00:00", false, JsonValue.FALSE),
          new ProfileSegment("01:00:00", true, JsonValue.NULL),
          new ProfileSegment("02:00:00", false, JsonValue.TRUE),
          new ProfileSegment("03:00:00", true, JsonValue.NULL),
          new ProfileSegment("04:00:00", false, JsonValue.FALSE));
      assertEquals(expectedMBSegments, mbProfile);

      final var nProfiles = externalDataset.profiles().get(newProfile.name());
      assertEquals(4, nProfiles.size());
      final var expectedNSegments = List.of(
          new ProfileSegment("00:00:00", false, JsonValue.TRUE),
          new ProfileSegment("00:30:00", false, JsonValue.FALSE),
          new ProfileSegment("01:00:00", true, JsonValue.NULL),
          new ProfileSegment("01:30:00", false, JsonValue.TRUE));
      assertEquals(expectedNSegments, nProfiles);
    }

    @Test
    void extendAndAdd() throws IOException {
      hasura.extendExternalDataset(datasetId, List.of(myBooleanExtension, newProfile));
      final var externalDataset = hasura.getExternalDataset(planId, datasetId);

      // Check Properties
      assertTrue(externalDataset.simulationDatasetId().isEmpty());
      assertEquals(datasetOffset, externalDataset.startOffset());
      assertEquals(2, externalDataset.profiles().size());
      assertTrue(externalDataset.profiles().containsKey(myBooleanExtension.name()));
      assertTrue(externalDataset.profiles().containsKey(newProfile.name()));

      // Check Profile Segments
      final var mbProfile = externalDataset.profiles().get(myBooleanExtension.name());
      assertEquals(7, mbProfile.size());
      final var expectedMBSegments = List.of(
          new ProfileSegment("00:00:00", false, JsonValue.FALSE),
          new ProfileSegment("01:00:00", true, JsonValue.NULL),
          new ProfileSegment("02:00:00", false, JsonValue.TRUE),
          new ProfileSegment("03:00:00", true, JsonValue.NULL),
          new ProfileSegment("04:00:00", false, JsonValue.FALSE),
          new ProfileSegment("05:00:00", false, JsonValue.FALSE),
          new ProfileSegment("05:30:00", false, JsonValue.TRUE));
      assertEquals(expectedMBSegments, mbProfile);

      final var nProfiles = externalDataset.profiles().get(newProfile.name());
      assertEquals(4, nProfiles.size());
      final var expectedNSegments = List.of(
          new ProfileSegment("00:00:00", false, JsonValue.TRUE),
          new ProfileSegment("00:30:00", false, JsonValue.FALSE),
          new ProfileSegment("01:00:00", true, JsonValue.NULL),
          new ProfileSegment("01:30:00", false, JsonValue.TRUE));
      assertEquals(expectedNSegments, nProfiles);
    }
  }
}

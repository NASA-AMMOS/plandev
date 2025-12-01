package gov.nasa.jpl.plandev.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.plandev.e2e.utils.GatewayRequests;
import gov.nasa.jpl.plandev.e2e.utils.HasuraRequests;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EffectiveArgumentsTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Per-Test Data
  private int modelId;

  // Cross-Test Constants
  final JsonObject biteSizeOne = Json.createObjectBuilder().add("biteSize", 1.0).build();


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
          "Effective Arguments Tests");
    }
  }

  @AfterEach
  void afterEach() throws IOException {
    // Remove Model
    hasura.deleteMissionModel(modelId);
  }

  @Nested
  class ModelEffectiveArguments {
    @Test
    void defaultArgs() throws IOException {
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, JsonValue.EMPTY_JSON_OBJECT);
      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.getString("initialDataPath"));
      assertEquals("Chiquita", args.getString("initialProducer"));
      assertEquals(200, args.getInt("initialPlantCount"));
      final var expectedInCons = Json.createObjectBuilder()
                                     .add("flag", "A")
                                     .add("fruit", 4.0)
                                     .add("peel", 4.0)
                                     .build();
      assertEquals(expectedInCons, args.getJsonObject("initialConditions"));
    }

    @Test
    void passedArgs() throws IOException {
      final var passedArgs = Json.createObjectBuilder().add("initialProducer", "Albany").build();
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, passedArgs);
      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.getString("initialDataPath"));
      assertEquals("Albany", args.getString("initialProducer"));
      assertEquals(200, args.getInt("initialPlantCount"));
      final var expectedInCons = Json.createObjectBuilder()
                                     .add("flag", "A")
                                     .add("fruit", 4.0)
                                     .add("peel", 4.0)
                                     .build();
      assertEquals(expectedInCons, args.getJsonObject("initialConditions"));
    }

    @Test
    void errors() throws IOException {
      final var passedArgs = Json.createObjectBuilder().add("fakeParam", "Albany").build();
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, passedArgs);
      assertFalse(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isPresent());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.getString("initialDataPath"));
      assertEquals("Chiquita", args.getString("initialProducer"));
      assertEquals(200, args.getInt("initialPlantCount"));
      final var expectedInCons = Json.createObjectBuilder()
                                     .add("flag", "A")
                                     .add("fruit", 4.0)
                                     .add("peel", 4.0)
                                     .build();
      assertEquals(expectedInCons, args.getJsonObject("initialConditions"));

      // Check returned Errors
      final var errors = effectiveArgs.errors().get().asJsonObject();
      assertEquals(3, errors.size());
      assertEquals(JsonArray.EMPTY_JSON_ARRAY, errors.getJsonArray("missingArguments"));
      assertEquals(JsonArray.EMPTY_JSON_ARRAY, errors.getJsonArray("unconstructableArguments"));
      final var expectedError = Json.createArrayBuilder().add("fakeParam").build();
      assertEquals(expectedError, errors.getJsonArray("extraneousArguments"));
    }
  }

  @Nested
  class ActivityEffectiveArguments {
    @Test
    void singleActivityDefaultArguments() throws IOException {
      final var effectiveArgs = hasura.getEffectiveActivityArguments(
          modelId,
          "BiteBanana",
          JsonValue.EMPTY_JSON_OBJECT);

      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      final var args = effectiveArgs.arguments().get();
      assertEquals(1, args.size());
      assertEquals(biteSizeOne, args);
    }

    @Test
    void singleActivityPassedArguments() throws IOException {
      final JsonObject biteSizeTwo = Json.createObjectBuilder().add("biteSize", 2.0).build();
      final var effectiveArgs = hasura.getEffectiveActivityArguments(
          modelId,
          "BiteBanana",
          biteSizeTwo);

      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      final var args = effectiveArgs.arguments().get();
      assertEquals(1, args.size());
      assertEquals(biteSizeTwo, args);
    }

    @Test
    void bulkActivitiesPassedArguments() throws IOException {
      final var activities = List.of(
          Pair.of("BiteBanana", biteSizeOne),
          Pair.of("BakeBananaBread", Json.createObjectBuilder()
                                         .add("tbSugar", 1)
                                         .add("glutenFree", true)
                                         .build()),
          Pair.of("BakeBananaBread", Json.createObjectBuilder()
                                         .add("tbSugar", 2)
                                         .add("glutenFree", true)
                                         .add("temperature", 400)
                                         .build()));
      final var effectiveArgs = hasura.getEffectiveActivityArgumentsBulk(modelId, activities);
      assertEquals(activities.size(), effectiveArgs.size());

      for (int i = 0; i < activities.size(); ++i) {
        assertTrue(effectiveArgs.get(i).success());
        assertTrue(effectiveArgs.get(i).arguments().isPresent());
        assertEquals(activities.get(i).getLeft(), effectiveArgs.get(i).activityType());
      }

      assertEquals(350, effectiveArgs.get(1).arguments().get().getInt("temperature")); // default arg value
      assertEquals(400, effectiveArgs.get(2).arguments().get().getInt("temperature")); // passed arg value
    }

    @Test
    void bulkActivitiesSingleError() throws IOException {
      final var activities = List.of(
          Pair.of("BiteBanana", biteSizeOne),
          Pair.of("BakeBananaBread", JsonObject.EMPTY_JSON_OBJECT));
      final var effectiveArgs = hasura.getEffectiveActivityArgumentsBulk(modelId, activities);
      assertEquals(activities.size(), effectiveArgs.size());

      final var biteBanana = effectiveArgs.get(0);
      final var bakeBananaBread = effectiveArgs.get(1);

      // NonError activity
      assertEquals("BiteBanana", biteBanana.activityType());
      assertTrue(biteBanana.success());
      assertTrue(biteBanana.arguments().isPresent());
      assertEquals(biteSizeOne, biteBanana.arguments().get());
      assertFalse(biteBanana.errors().isPresent());

      // Error Activity
      assertEquals("BakeBananaBread", bakeBananaBread.activityType());
      assertFalse(bakeBananaBread.success());
      assertTrue(bakeBananaBread.arguments().isPresent());
      assertTrue(bakeBananaBread.errors().isPresent());
      assertEquals(Json.createObjectBuilder().add("temperature", 350.0).build(), bakeBananaBread.arguments().get());
      final var expectedErrors = Json.createObjectBuilder()
                                     .add("extraneousArguments", JsonValue.EMPTY_JSON_ARRAY)
                                     .add(
                                         "missingArguments",
                                         Json.createArrayBuilder().add("tbSugar").add("glutenFree"))
                                     .add("unconstructableArguments", JsonValue.EMPTY_JSON_ARRAY)
                                     .build();
      assertEquals(expectedErrors, bakeBananaBread.errors().get());
    }

    @Test
    void bulkActivitiesMultipleErrors() throws IOException {
      final var activities = List.of(
          Pair.of("BiteBananaDOESNOTEXIST", biteSizeOne),
          Pair.of("BakeBananaBread", JsonObject.EMPTY_JSON_OBJECT),
          Pair.of("BiteBanana", JsonObject.EMPTY_JSON_OBJECT));
      final var effectiveArgs = hasura.getEffectiveActivityArgumentsBulk(modelId, activities);
      assertEquals(activities.size(), effectiveArgs.size());

      final var biteBananaDNE = effectiveArgs.get(0);
      final var bakeBananaBread = effectiveArgs.get(1);
      final var biteBanana = effectiveArgs.get(2);


      // BiteBananaDOESNOTEXIST
      assertEquals("BiteBananaDOESNOTEXIST", biteBananaDNE.activityType());
      assertFalse(biteBananaDNE.success());
      assertTrue(biteBananaDNE.arguments().isEmpty());
      assertTrue(biteBananaDNE.errors().isPresent());
      assertEquals("No such activity type", ((JsonString) biteBananaDNE.errors().get()).getString());

      // BakeBananaBread
      assertEquals("BakeBananaBread", bakeBananaBread.activityType());
      assertFalse(bakeBananaBread.success());
      assertTrue(bakeBananaBread.arguments().isPresent());
      assertTrue(bakeBananaBread.errors().isPresent());
      assertEquals(Json.createObjectBuilder().add("temperature", 350.0).build(), bakeBananaBread.arguments().get());
      final var expectedErrors = Json.createObjectBuilder()
                                     .add("extraneousArguments", JsonValue.EMPTY_JSON_ARRAY)
                                     .add(
                                         "missingArguments",
                                         Json.createArrayBuilder().add("tbSugar").add("glutenFree"))
                                     .add("unconstructableArguments", JsonValue.EMPTY_JSON_ARRAY)
                                     .build();
      assertEquals(expectedErrors, bakeBananaBread.errors().get());

      // BiteBanana activity
      assertEquals("BiteBanana", biteBanana.activityType());
      assertTrue(biteBanana.success());
      assertTrue(biteBanana.arguments().isPresent());
      assertEquals(biteSizeOne, biteBanana.arguments().get());
      assertFalse(biteBanana.errors().isPresent());

    }
  }
}

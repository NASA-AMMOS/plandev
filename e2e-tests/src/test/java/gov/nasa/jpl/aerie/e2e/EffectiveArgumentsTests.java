package gov.nasa.jpl.aerie.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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
  final ObjectNode biteSizeOne = JsonNodeFactory.instance.objectNode().put("biteSize", 1.0);

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
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, JsonNodeFactory.instance.objectNode());
      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.get("initialDataPath").textValue());
      assertEquals("Chiquita", args.get("initialProducer").textValue());
      assertEquals(200, args.get("initialPlantCount").intValue());
      final var expectedInCons = JsonNodeFactory.instance.objectNode()
                                     .put("flag", "A")
                                     .put("fruit", 4.0)
                                     .put("peel", 4.0)
                                     ;
      assertEquals(expectedInCons, args.get("initialConditions"));
    }

    @Test
    void passedArgs() throws IOException {
      final var passedArgs = JsonNodeFactory.instance.objectNode().put("initialProducer", "Albany");
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, passedArgs);
      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.get("initialDataPath").textValue());
      assertEquals("Albany", args.get("initialProducer").textValue());
      assertEquals(200, args.get("initialPlantCount").intValue());
      final var expectedInCons = JsonNodeFactory.instance.objectNode()
                                     .put("flag", "A")
                                     .put("fruit", 4.0)
                                     .put("peel", 4.0)
                                     ;
      assertEquals(expectedInCons, args.get("initialConditions"));
    }

    @Test
    void errors() throws IOException {
      final var passedArgs = JsonNodeFactory.instance.objectNode().put("fakeParam", "Albany");
      final var effectiveArgs = hasura.getEffectiveModelArguments(modelId, passedArgs);
      assertFalse(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isPresent());

      // Check returned Arguments
      final var args = effectiveArgs.arguments().get();
      assertEquals(4, args.size());
      assertEquals("/etc/os-release", args.get("initialDataPath").textValue());
      assertEquals("Chiquita", args.get("initialProducer").textValue());
      assertEquals(200, args.get("initialPlantCount").intValue());
      final var expectedInCons = JsonNodeFactory.instance.objectNode()
                                     .put("flag", "A")
                                     .put("fruit", 4.0)
                                     .put("peel", 4.0)
                                     ;
      assertEquals(expectedInCons, args.get("initialConditions"));

      // Check returned Errors
      final var errors = effectiveArgs.errors().get();
      assertEquals(3, errors.size());
      assertEquals(JsonNodeFactory.instance.arrayNode(), errors.get("missingArguments"));
      assertEquals(JsonNodeFactory.instance.arrayNode(), errors.get("unconstructableArguments"));
      final var expectedError = JsonNodeFactory.instance.arrayNode().add("fakeParam");
      assertEquals(expectedError, errors.get("extraneousArguments"));
    }
  }

  @Nested
  class ActivityEffectiveArguments {
    @Test
    void singleActivityDefaultArguments() throws IOException {
      final var effectiveArgs = hasura.getEffectiveActivityArguments(
          modelId,
          "BiteBanana",
          JsonNodeFactory.instance.objectNode());

      assertTrue(effectiveArgs.success());
      assertTrue(effectiveArgs.arguments().isPresent());
      assertTrue(effectiveArgs.errors().isEmpty());

      final var args = effectiveArgs.arguments().get();
      assertEquals(1, args.size());
      assertEquals(biteSizeOne, args);
    }

    @Test
    void singleActivityPassedArguments() throws IOException {
      final ObjectNode biteSizeTwo = JsonNodeFactory.instance.objectNode().put("biteSize", 2.0);
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
          Pair.of("BakeBananaBread", JsonNodeFactory.instance.objectNode()
                                         .put("tbSugar", 1)
                                         .put("glutenFree", true)
                                         ),
          Pair.of("BakeBananaBread", JsonNodeFactory.instance.objectNode()
                                         .put("tbSugar", 2)
                                         .put("glutenFree", true)
                                         .put("temperature", 400)
                                         ));
      final var effectiveArgs = hasura.getEffectiveActivityArgumentsBulk(modelId, activities);
      assertEquals(activities.size(), effectiveArgs.size());

      for (int i = 0; i < activities.size(); ++i) {
        assertTrue(effectiveArgs.get(i).success());
        assertTrue(effectiveArgs.get(i).arguments().isPresent());
        assertEquals(activities.get(i).getLeft(), effectiveArgs.get(i).activityType());
      }

      assertEquals(350, effectiveArgs.get(1).arguments().get().get("temperature").intValue()); // default arg value
      assertEquals(400, effectiveArgs.get(2).arguments().get().get("temperature").intValue()); // passed arg value
    }

    @Test
    void bulkActivitiesSingleError() throws IOException {
      final var activities = List.of(
          Pair.of("BiteBanana", biteSizeOne),
          Pair.of("BakeBananaBread", JsonNodeFactory.instance.objectNode()));
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
      assertEquals(JsonNodeFactory.instance.objectNode().put("temperature", 350.0), bakeBananaBread.arguments().get());
      final var expectedErrors = JsonNodeFactory.instance.objectNode()
                                     .set("extraneousArguments", JsonNodeFactory.instance.arrayNode())
                                     .add(
                                         "missingArguments",
                                         JsonNodeFactory.instance.arrayNode().add("tbSugar").add("glutenFree"))
                                     .set("unconstructableArguments", JsonNodeFactory.instance.arrayNode())
                                     ;
      assertEquals(expectedErrors, bakeBananaBread.errors().get());
    }

    @Test
    void bulkActivitiesMultipleErrors() throws IOException {
      final var activities = List.of(
          Pair.of("BiteBananaDOESNOTEXIST", biteSizeOne),
          Pair.of("BakeBananaBread", JsonNodeFactory.instance.objectNode()),
          Pair.of("BiteBanana", JsonNodeFactory.instance.objectNode()));
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
      assertEquals("No such activity type", biteBananaDNE.errors().get().textValue());

      // BakeBananaBread
      assertEquals("BakeBananaBread", bakeBananaBread.activityType());
      assertFalse(bakeBananaBread.success());
      assertTrue(bakeBananaBread.arguments().isPresent());
      assertTrue(bakeBananaBread.errors().isPresent());
      assertEquals(JsonNodeFactory.instance.objectNode().put("temperature", 350.0), bakeBananaBread.arguments().get());
      final var expectedErrors = JsonNodeFactory.instance.objectNode()
                                     .set("extraneousArguments", JsonNodeFactory.instance.arrayNode())
                                     .add(
                                         "missingArguments",
                                         JsonNodeFactory.instance.arrayNode().add("tbSugar").add("glutenFree"))
                                     .set("unconstructableArguments", JsonNodeFactory.instance.arrayNode())
                                     ;
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

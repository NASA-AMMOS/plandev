package gov.nasa.jpl.plandev.merlin.server.models;

import gov.nasa.jpl.plandev.foomissionmodel.generated.GeneratedModelType;
import gov.nasa.jpl.plandev.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.plandev.types.SerializedActivity;
import gov.nasa.jpl.plandev.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.plandev.merlin.server.services.MissionModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class MissionModelTest {

  private DirectiveTypeRegistry<?> registry;

  @BeforeEach
  public void initialize() {
      this.registry = DirectiveTypeRegistry.extract(new GeneratedModelType());
  }

  @AfterEach
  public void teardown() {
    // TODO: [PLANDEV-1516] Teardown the model to release any system resources (e.g. threads).
  }

  @Test
  public void shouldGetActivityTypeList() {
    // GIVEN
    final Map<String, ActivityType> expectedTypes = Map.of(
          "foo", new ActivityType(
              "foo",
              List.of(
                  new Parameter("x", ValueSchema.INT),
                  new Parameter("y", ValueSchema.STRING),
                  new Parameter("z", ValueSchema.INT),
                  new Parameter("vecs", ValueSchema.ofSeries(ValueSchema.ofSeries(ValueSchema.REAL)))),
              List.of(),
              ValueSchema.ofStruct(Map.of()),
              Optional.empty(),
              Optional.empty()
          ));

    // WHEN
    final var activityTypes = new HashMap<String, ActivityType>();
    registry.directiveTypes().forEach((name, specType) -> activityTypes.put(
        name,
        new ActivityType(name,
                         specType.getInputType().getParameters(),
                         specType.getInputType().getRequiredParameters(),
                         specType.getOutputType().getSchema(),
                         Optional.empty(),
                         Optional.empty()
        )));

    // THEN
    assertTrue(activityTypes.entrySet().containsAll(expectedTypes.entrySet()));
  }

  @Test
  public void shouldGetActivityType() throws MissionModelService.NoSuchActivityTypeException {
    // GIVEN
    final ActivityType expectedType = new ActivityType(
          "foo",
          List.of(
              new Parameter("x", ValueSchema.INT),
              new Parameter("y", ValueSchema.STRING),
              new Parameter("z", ValueSchema.INT),
              new Parameter("vecs", ValueSchema.ofSeries(ValueSchema.ofSeries(ValueSchema.REAL)))),
          List.of(),
          ValueSchema.ofStruct(Map.of()),
          Optional.empty(),
          Optional.empty()
      );

    // WHEN
    final var typeName = expectedType.name();
    final var specType = Optional
        .ofNullable(registry.directiveTypes().get(typeName))
        .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(typeName));

    final var type = new ActivityType(
        typeName,
        specType.getInputType().getParameters(),
        specType.getInputType().getRequiredParameters(),
        specType.getOutputType().getSchema(),
        Optional.empty(),
        Optional.empty()
    );

    // THEN
    assertEquals(expectedType, type);
  }

  @Test
  public void shouldNotGetActivityTypeForNonexistentActivityType() {
    // GIVEN
    final String activityId = "nonexistent activity type";

    // THEN
    assertThrows(MissionModelService.NoSuchActivityTypeException.class, () -> {
    // WHEN
        final var specType = Optional
            .ofNullable(registry.directiveTypes().get(activityId))
            .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(activityId));

        new ActivityType(
            activityId,
            specType.getInputType().getParameters(),
            specType.getInputType().getRequiredParameters(),
            specType.getOutputType().getSchema(),
            Optional.empty(),
            Optional.empty()
        );
    });
  }

  @Test
  public void shouldInstantiateActivityInstance()
  throws MissionModelService.NoSuchActivityTypeException, InstantiationException
  {
    // GIVEN
    final var typeName = "foo";
    final var arguments = new HashMap<>(Map.of("x", SerializedValue.of(0),
                                               "y", SerializedValue.of("test"),
                                               "z", SerializedValue.of(1)));

    // WHEN
    final var activity = new SerializedActivity(typeName, arguments);
    final var specType = Optional
        .ofNullable(registry.directiveTypes().get(activity.getTypeName()))
        .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(activity.getTypeName()));
    final var failures = specType.getInputType().validateArguments(activity.getArguments());

    // THEN
    assertTrue(failures.isEmpty());
  }

  @Test
  public void shouldNotInstantiateActivityInstanceWithIncorrectParameterType() {
    // GIVEN
    final var typeName = "foo";
    final var parameters = new HashMap<>(Map.of("x", SerializedValue.of(0),
                                                "y", SerializedValue.of(1.0)));

    // WHEN
    final var thrown = assertThrows(InstantiationException.class, () -> {
        final var activity = new SerializedActivity(typeName, parameters);
        final var specType = Optional
            .ofNullable(registry.directiveTypes().get(activity.getTypeName()))
            .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(activity.getTypeName()));
        specType.getInputType().validateArguments(activity.getArguments());
    });

    // THEN
    assertTrue(thrown.extraneousArguments.isEmpty());
    assertEquals(List.of("z"), thrown.missingArguments.stream().map(a -> a.parameterName()).toList());
    assertEquals(List.of("y"), thrown.unconstructableArguments.stream().map(a -> a.parameterName()).toList());
    assertEquals(List.of("x", "y", "vecs"), thrown.validArguments.stream().map(a -> a.parameterName()).toList());
  }

  @Test
  public void shouldNotInstantiateActivityInstanceWithExtraParameter() {
    // GIVEN
    final var typeName = "foo";
    final var parameters = new HashMap<>(Map.of("Nonexistent", SerializedValue.of("")));

    // WHEN
    final var thrown = assertThrows(InstantiationException.class, () -> {
        final var activity = new SerializedActivity(typeName, parameters);
        final var specType = Optional
            .ofNullable(registry.directiveTypes().get(activity.getTypeName()))
            .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(activity.getTypeName()));
        specType.getInputType().validateArguments(activity.getArguments());
    });

    // THEN
    assertEquals(List.of("Nonexistent"), thrown.extraneousArguments.stream().map(a -> a.parameterName()).toList());
    assertEquals(List.of("z"), thrown.missingArguments.stream().map(a -> a.parameterName()).toList());
    assertTrue(thrown.unconstructableArguments.isEmpty());
    assertEquals(List.of("x", "y", "vecs"), thrown.validArguments.stream().map(a -> a.parameterName()).toList());
  }
}

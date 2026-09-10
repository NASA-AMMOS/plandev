package gov.nasa.ammos.plandev.merlin.server.services;

import gov.nasa.ammos.plandev.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.ammos.plandev.merlin.driver.MissionModel;
import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader;
import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader.MissionModelLoadException;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.types.MissionModelId;
import gov.nasa.ammos.plandev.types.Plan;
import gov.nasa.ammos.plandev.types.SerializedActivity;
import gov.nasa.ammos.plandev.merlin.driver.SimulationDriver;
import gov.nasa.ammos.plandev.merlin.driver.SimulationResults;
import gov.nasa.ammos.plandev.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType.Parameter;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.ammos.plandev.merlin.protocol.model.ModelType;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityType;
import gov.nasa.ammos.plandev.merlin.server.models.MissionModelJar;
import gov.nasa.ammos.plandev.merlin.server.remotes.MissionModelRepository;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Implements the missionModel service {@link MissionModelService} interface on a set of local domain objects.
 *
 * May throw unchecked exceptions:
 * * {@link MissionModelLoadException}: When a mission model cannot be loaded from the JAR provided by the
 * connected mission model repository.
 */
public final class LocalMissionModelService implements MissionModelService {
  private static final Logger log = LoggerFactory.getLogger(LocalMissionModelService.class);

  /** The only {@code model_type} that has a JAR. Everything else is served from stored metadata. */
  private static final String MODEL_TYPE_JAR = "jar";

  private final Path missionModelDataPath;
  private final MissionModelRepository missionModelRepository;
  private final Instant untruePlanStart;

  public LocalMissionModelService(
      final Path missionModelDataPath,
      final MissionModelRepository missionModelRepository,
      final Instant untruePlanStart
  ) {
    this.missionModelDataPath = missionModelDataPath;
    this.missionModelRepository = missionModelRepository;
    this.untruePlanStart = untruePlanStart;
  }

  @Override
  public Map<MissionModelId, MissionModelJar> getMissionModels() {
    return this.missionModelRepository.getAllMissionModels();
  }

  @Override
  public MissionModelJar getMissionModelById(final MissionModelId missionModelId) throws NoSuchMissionModelException {
    try {
      return this.missionModelRepository.getMissionModel(missionModelId);
    } catch (NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  /**
   * True when this model has no JAR to classload, so every read path below must be served from what is
   * STORED rather than from a loaded {@link MissionModel}.
   *
   * <p>Phrased as "not a jar" rather than as "is declared" deliberately. {@code model_type} is a
   * discriminator that will gain values -- an external backend is the one already designed -- and every
   * branch below is about the ABSENCE of a JAR, not about which non-JAR kind it is. Written the other
   * way round, each new kind would silently fall through to {@code Path.resolve(null)}.
   */
  private boolean isModelWithoutJar(final MissionModelId missionModelId)
  throws NoSuchMissionModelException {
    return !MODEL_TYPE_JAR.equals(getMissionModelById(missionModelId).modelType);
  }

  /**
   * Presence-only validation of arguments against stored parameter metadata.
   *
   * <p>Flags unrecognized argument names and missing required parameters, and deliberately stops there.
   * Deep conformance of a {@link SerializedValue} to its {@link ValueSchema} is enforced by
   * {@link ExternalResultsGate} at ingest, where the results and the declaration are checked against
   * each other in one place. Doing it here as well would put the same rule in two places with two
   * messages, and this one is on the interactive editing path where a half-filled form is normal.
   */
  private static List<ValidationNotice> validateAgainstStoredParameters(
      final List<Parameter> parameters,
      final List<String> requiredParameters,
      final Map<String, SerializedValue> arguments)
  {
    final var notices = new ArrayList<ValidationNotice>();
    final var parameterNames = parameters.stream().map(Parameter::name).collect(Collectors.toSet());

    for (final var argName : arguments.keySet()) {
      if (!parameterNames.contains(argName)) {
        notices.add(new ValidationNotice(List.of(argName), "unrecognized parameter '" + argName + "'"));
      }
    }
    for (final var required : requiredParameters) {
      if (!arguments.containsKey(required)) {
        notices.add(new ValidationNotice(List.of(required), "missing required parameter '" + required + "'"));
      }
    }
    return notices;
  }

  /**
   * Why a JAR-less model cannot be simulated, as a sentence for a user.
   *
   * <p>The model's own declaration is the authority and its text is repeated VERBATIM, so that nothing
   * here -- and nothing in the UI -- contains a sentence about any particular producer. A model that
   * declares nothing gets the generic fallback.
   */
  private static String whySimulationUnavailable(final MissionModelJar model, final MissionModelId modelId) {
    final var declared = declaredCapabilityReason(model.externalCapabilities, "simulation");
    if (declared != null) return declared;
    return ("Mission model %s (\"%s\") has no JAR and no simulator behind it: its activity, resource and "
            + "configuration types were declared to PlanDev, and its results were recorded elsewhere and "
            + "imported. Import another recorded run to see different results.")
        .formatted(modelId, model.name);
  }

  /** The {@code reason} a model's stored capabilities give for {@code capability} being unsupported, or
   *  null if the capability is absent, supported, or carries no reason. Absent means unsupported. */
  private static String declaredCapabilityReason(final String capabilitiesJson, final String capability) {
    if (capabilitiesJson == null || capabilitiesJson.isBlank()) return null;
    try (final var reader = javax.json.Json.createReader(new java.io.StringReader(capabilitiesJson))) {
      if (!(reader.readObject().get(capability) instanceof javax.json.JsonObject entry)) return null;
      if (entry.getBoolean("supported", false)) return null;
      final var reason = entry.getString("reason", "");
      return reason.isBlank() ? null : reason;
    } catch (final Exception ex) {                       // NOSONAR -- unparseable means undeclared
      return null;
    }
  }

  @Override
  public Map<String, ValueSchema> getResourceSchemas(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (isModelWithoutJar(missionModelId)) {
      // No JAR to instantiate: resource schemas come from the stored resource_type rows, which are the
      // same rows the UI and the constraints codegen read.
      return this.missionModelRepository.getResourceTypes(missionModelId);
    }
    // TODO: [AERIE-1516] Teardown the missionModel after use to release any system resources (e.g. threads).
    final var schemas = new HashMap<String, ValueSchema>();

    for (final var entry : loadAndInstantiateMissionModel(missionModelId).getResources().entrySet()) {
      final var name = entry.getKey();
      final var resource = entry.getValue();
      schemas.put(name, resource.getOutputType().getSchema());
    }

    return schemas;
  }

  /**
   * Get information about all activity types in the named mission model.
   *
   * @param missionModelId The ID of the mission model to load.
   * @return The set of all activity types in the named mission model, indexed by name.
   * @throws NoSuchMissionModelException If no mission model is known by the given ID.
   */
  @Override
  public Map<String, ActivityType> getActivityTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException
  {
    return missionModelRepository.getActivityTypes(missionModelId);
  }

  /**
   * Validate that a set of activity parameters conforms to the expectations of a named mission model.
   *
   * @param missionModelId The ID of the mission model to load.
   * @param activity The serialized activity to validate against the named mission model.
   * @return A list of validation errors that is empty if validation succeeds.
   * @throws NoSuchMissionModelException If no mission model is known by the given ID.
   * @throws MissionModelLoadException If the mission model cannot be loaded -- the JAR may be invalid, or the mission model
   * it contains may not abide by the expected contract at load time.
   */
  @Override
  public List<ValidationNotice> validateActivityArguments(final MissionModelId missionModelId, final SerializedActivity activity)
  throws NoSuchMissionModelException, MissionModelLoadException, InstantiationException
  {
    if (isModelWithoutJar(missionModelId)) {
      final var activityType = getActivityTypes(missionModelId).get(activity.getTypeName());
      if (activityType == null) return List.of(new ValidationNotice(List.of(), "unknown activity type"));
      return validateAgainstStoredParameters(
          activityType.parameters(), activityType.requiredParameters(), activity.getArguments());
    }
    // TODO: [AERIE-1516] Teardown the missionModel after use to release any system resources (e.g. threads).
    final var modelType = this.loadMissionModelType(missionModelId);
    final var registry = DirectiveTypeRegistry.extract(modelType);
    final var directiveType = registry.directiveTypes().get(activity.getTypeName());
    if (directiveType == null) return List.of(new ValidationNotice(List.of(), "unknown activity type"));
    return directiveType.getInputType().validateArguments(activity.getArguments());
  }

  public List<BulkArgumentValidationResponse> validateActivityArgumentsBulk(
      final MissionModelId missionModelId,
      final List<ActivityDirectiveForValidation> activities) {
    final Map<String, ActivityType> storedActivityTypes;
    try {
      storedActivityTypes = isModelWithoutJar(missionModelId) ? getActivityTypes(missionModelId) : null;
    } catch (final NoSuchMissionModelException e) {
      return activities.stream()
          .<BulkArgumentValidationResponse>map(directive -> new BulkArgumentValidationResponse.NoSuchMissionModelError(e))
          .collect(Collectors.toList());
    }
    if (storedActivityTypes != null) {
      return activities.stream().map(directive -> {
        final var typeName = directive.activity().getTypeName();
        final var activityType = storedActivityTypes.get(typeName);
        if (activityType == null) {
          return (BulkArgumentValidationResponse)
              new BulkArgumentValidationResponse.NoSuchActivityError(new NoSuchActivityTypeException(typeName));
        }
        final var notices = validateAgainstStoredParameters(
            activityType.parameters(), activityType.requiredParameters(), directive.activity().getArguments());
        return notices.isEmpty()
            ? new BulkArgumentValidationResponse.Success()
            : new BulkArgumentValidationResponse.Validation(notices);
      }).collect(Collectors.toList());
    }

    // load mission model once for all activities
    ModelType<?, ?> modelType;
    try {
      modelType = this.loadMissionModelType(missionModelId);
      // try and catch NoSuchMissionModel here, so we can serialize it out to each activity validation
      // rather than catching it at a higher level in the workerLoop itself
    } catch (NoSuchMissionModelException e) {
      return activities.stream()
          .map(directive -> new BulkArgumentValidationResponse.NoSuchMissionModelError(e))
          .collect(Collectors.toList());
    } catch (MissionModelLoadException e) {
      log.error("Caught MissionModelLoadException, skipping this batch but leaving validations pending...");
      log.error(e.toString());
      return List.of();
    }
    final var registry = DirectiveTypeRegistry.extract(modelType);

    // map all directives to validation response
    return activities.stream().map((directive) -> {
      final var typeName = directive.activity().getTypeName();
      final var arguments = directive.activity().getArguments();

      try {
        final var directiveType = registry.directiveTypes().get(typeName);
        if (directiveType == null) {
          return new BulkArgumentValidationResponse.NoSuchActivityError(new NoSuchActivityTypeException(typeName));
        }

        final var notices = directiveType.getInputType().validateArguments(arguments);
        return notices.isEmpty()
            ? new BulkArgumentValidationResponse.Success()
            : new BulkArgumentValidationResponse.Validation(notices);
      } catch (InstantiationException e) {
        return new BulkArgumentValidationResponse.InstantiationError(e);
      }
    }).collect(Collectors.toList());
  }

  public Map<MissionModelId, List<ActivityDirectiveForValidation>> getUnvalidatedDirectives() {
    return missionModelRepository.getUnvalidatedDirectives();
  }

  public void updateDirectiveValidations(List<Pair<ActivityDirectiveForValidation, BulkArgumentValidationResponse>> updates) {
    missionModelRepository.updateDirectiveValidations(updates);
  }

  /**
   * Validate that a set of activity parameters conforms to the expectations of a named mission model.
   *
   * @param missionModelId The ID of the mission model to load.
   * @param activities The serialized activities to perform instantiation validation against the named mission model.
   * @return A map of validation errors mapping activity instance ID to failure message. If validation succeeds the map is empty.
   */
  @Override
  public Map<ActivityDirectiveId, ActivityInstantiationFailure>
  validateActivityInstantiations(final MissionModelId missionModelId,
                                 final Map<ActivityDirectiveId, SerializedActivity> activities)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (isModelWithoutJar(missionModelId)) {
      // Nothing to construct without a JAR, so the only failure that can be detected is a type the
      // model never declared.
      final var activityTypes = getActivityTypes(missionModelId);
      final var failures = new HashMap<ActivityDirectiveId, ActivityInstantiationFailure>();
      activities.forEach((id, activity) -> {
        final var typeName = activity.getTypeName();
        if (!activityTypes.containsKey(typeName)) {
          failures.put(id, new ActivityInstantiationFailure.NoSuchActivityType(new NoSuchActivityTypeException(typeName)));
        }
      });
      return failures;
    }
    final var factory = this.loadMissionModelType(missionModelId);
    final var registry = DirectiveTypeRegistry.extract(factory);

    final var failures = new HashMap<ActivityDirectiveId, ActivityInstantiationFailure>();

    for (final var entry : activities.entrySet()) {
      final var id = entry.getKey();
      final var act = entry.getValue();
      try {
        // The return value is intentionally ignored - we are only interested in failures
        final var specType = Optional
        .ofNullable(registry.directiveTypes().get(act.getTypeName()))
        .orElseThrow(() -> new MissionModelService.NoSuchActivityTypeException(act.getTypeName()));
        specType.getInputType().getEffectiveArguments(act.getArguments());
      } catch (final NoSuchActivityTypeException ex) {
        failures.put(id, new ActivityInstantiationFailure.NoSuchActivityType(ex));
      } catch (final InstantiationException ex) {
        failures.put(id, new ActivityInstantiationFailure.InstantiationFailure(ex));
      }
    }

    return failures;
  }

  @Override
  public List<BulkEffectiveArgumentResponse> getActivityEffectiveArgumentsBulk(
      final MissionModelId missionModelId,
      final List<SerializedActivity> serializedActivities)
  throws NoSuchMissionModelException, MissionModelLoadException {
      if (isModelWithoutJar(missionModelId)) {
        // PlanDev stores no defaults for a declared model (registerModelTypes carries name + schema and
        // nothing else), so there are no effective arguments to resolve: echo what was provided.
        final var activityTypes = getActivityTypes(missionModelId);
        return serializedActivities.stream().map(activity -> {
          final var typeName = activity.getTypeName();
          return activityTypes.containsKey(typeName)
              ? (BulkEffectiveArgumentResponse) new BulkEffectiveArgumentResponse.Success(activity)
              : new BulkEffectiveArgumentResponse.TypeFailure(new NoSuchActivityTypeException(typeName));
        }).collect(Collectors.toList());
      }
      final var modelType = this.loadMissionModelType(missionModelId);
      final var registry = DirectiveTypeRegistry.extract(modelType);
      final var response = new ArrayList<BulkEffectiveArgumentResponse>();

      for (final var activity : serializedActivities) {
        final var typeName = activity.getTypeName();

        try {
          final var directiveType = Optional
              .ofNullable(registry.directiveTypes().get(typeName))
              .orElseThrow(() -> new NoSuchActivityTypeException(activity.getTypeName()));

          response.add(new BulkEffectiveArgumentResponse.Success(
              new SerializedActivity(
              typeName,
              directiveType.getInputType().getEffectiveArguments(activity.getArguments())
          )));
        } catch (NoSuchActivityTypeException e) {
          response.add(new BulkEffectiveArgumentResponse.TypeFailure(e));
        } catch (InstantiationException e) {
          response.add(new BulkEffectiveArgumentResponse.InstantiationFailure(e));
        }
      }

      return response;
  }

  @Override
  public List<ValidationNotice> validateModelArguments(final MissionModelId missionModelId, final Map<String, SerializedValue> arguments)
  throws NoSuchMissionModelException,
         MissionModelLoadException,
         InstantiationException
  {
    if (isModelWithoutJar(missionModelId)) {
      final var parameters = this.missionModelRepository.getModelParameters(missionModelId);
      // Types not registered yet: skip rather than flag every argument as unknown, which is what an
      // empty declared parameter list would otherwise mean.
      if (parameters.isEmpty()) return List.of();
      // Requiredness is not stored for configuration parameters, so only the unrecognized-name check
      // applies here.
      return validateAgainstStoredParameters(parameters, List.of(), arguments);
    }
    return this.loadMissionModelType(missionModelId)
        .getConfigurationType()
        .validateArguments(arguments);
  }

  @Override
  public List<Parameter> getModelParameters(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (isModelWithoutJar(missionModelId)) {
      return this.missionModelRepository.getModelParameters(missionModelId);
    }
    return this.loadMissionModelType(missionModelId).getConfigurationType().getParameters();
  }

  @Override
  public Map<String, SerializedValue> getModelEffectiveArguments(final MissionModelId missionModelId, final Map<String, SerializedValue> arguments)
  throws NoSuchMissionModelException,
         MissionModelLoadException,
         InstantiationException
  {
    if (isModelWithoutJar(missionModelId)) return arguments;   // no stored defaults; echo what was given
    return this.loadMissionModelType(missionModelId)
        .getConfigurationType()
        .getEffectiveArguments(arguments);
  }

  /**
   * Validate that a set of activity parameters conforms to the expectations of a named mission model.
   *
   * @param plan The plan to be simulated. Contains the parameters defining the simulation to perform.
   * @return A set of samples over the course of the simulation.
   * @throws NoSuchMissionModelException If no mission model is known by the given ID.
   */
  @Override
  public SimulationResults runSimulation(
      final Plan plan,
      final Consumer<Duration> simulationExtentConsumer,
      final Supplier<Boolean> canceledListener,
      final SimulationResourceManager resourceManager)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    // Reachable from the Simulate button and from forceResim even with the UI gated, so it must refuse
    // here rather than rely on the client. Without this the null jar path reaches
    // loadAndInstantiateMissionModel -> Path.resolve(null) -> NPE, which the worker reports as
    // UNEXPECTED_SIMULATION_EXCEPTION with the cause visible only in the stack trace.
    if (isModelWithoutJar(plan.missionModelId())) {
      final var model = getMissionModelById(plan.missionModelId());
      throw new ExternalModelException(
          ExternalModelException.Kind.SIMULATION_UNSUPPORTED,
          whySimulationUnavailable(model, plan.missionModelId()));
    }
    final var config = plan.simulationConfiguration();
    if (config.isEmpty()) {
      log.warn(
          "No mission model configuration defined for mission model. Simulations will receive an empty set of configuration arguments.");
    }

    // TODO: [AERIE-1516] Teardown the mission model after use to release any system resources (e.g. threads).
    return SimulationDriver.simulate(
        loadAndInstantiateMissionModel(
            plan.missionModelId(),
            plan.planStartInstant(),
            SerializedValue.of(config)),
        plan.activityDirectives(),
        plan.simulationStartInstant(),
        plan.simulationDuration(),
        plan.planStartInstant(),
        plan.duration(),
        canceledListener,
        simulationExtentConsumer,
        resourceManager);
  }

  @Override
  public void refreshModelParameters(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (skipRefreshForModelWithoutJar(missionModelId, "refreshModelParameters")) return;
    this.missionModelRepository.updateModelParameters(missionModelId, getModelParameters(missionModelId));
  }

  @Override
  public void refreshActivityTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (skipRefreshForModelWithoutJar(missionModelId, "refreshActivityTypes")) return;
    final var modelType = this.loadMissionModelType(missionModelId);
    final var registry = DirectiveTypeRegistry.extract(modelType);
    final var activityTypes = new HashMap<String, ActivityType>();
    registry.directiveTypes().forEach((name, directiveType) -> {
      final var inputType = directiveType.getInputType();
      final var outputType = directiveType.getOutputType();
      activityTypes.put(
          name, new ActivityType(
              name,
              inputType.getParameters(),
              inputType.getRequiredParameters(),
              outputType.getSchema(),
              directiveType.getSubsystem(),
              directiveType.getDescription()
          ));
    });
    final var subsystems = modelType.getSubsystems();
    this.missionModelRepository.updateActivityTypes(missionModelId, activityTypes, subsystems);
  }

  @Override
  public void refreshResourceTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException {
    if (skipRefreshForModelWithoutJar(missionModelId, "refreshResourceTypes")) return;
    final var model = this.loadAndInstantiateMissionModel(missionModelId);
    this.missionModelRepository.updateResourceTypes(missionModelId, model.getResources());
  }

  /**
   * Whether a {@code refresh*} handler should do nothing at all, because the model has no JAR to derive
   * anything from.
   *
   * <p>This is the smallest change here and the one most likely to bite. All three refresh event
   * triggers are declared {@code insert: columns: "*"} (mission_model.yaml), so ALL THREE FIRE on every
   * mission_model insert -- a declared model's included. Two things go wrong without this guard:
   *
   * <ul>
   *   <li><b>Cosmetically:</b> the JAR path reaches {@code Path.resolve(null)} and NPEs, which lands
   *       {@code success: false} rows in refresh_activity_type_logs / refresh_model_parameter_logs /
   *       refresh_resource_type_logs. plandev-ui reads exactly those to paint a model BROKEN, so a
   *       working model looks broken on the models page.
   *   <li><b>Dangerously:</b> the triggers are ASYNCHRONOUS. A handler that instead *succeeded* by
   *       falling through would write derived metadata over the types {@code registerModelTypes} had
   *       just stored -- seconds after the import, silently.
   * </ul>
   *
   * <p>So: log and return. Not a throw, which puts a failed row in the log; not a fall-through, which
   * is the dangerous case.
   */
  private boolean skipRefreshForModelWithoutJar(final MissionModelId missionModelId, final String handler)
  throws NoSuchMissionModelException {
    if (!isModelWithoutJar(missionModelId)) return false;
    log.info("{}: mission model {} has no JAR; its types are declared rather than derived, so there is "
             + "nothing to refresh.", handler, missionModelId);
    return true;
  }

  @Override
  public void registerModelTypes(
      final MissionModelId missionModelId,
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resourceTypes,
      final List<Parameter> parameters
  ) throws NoSuchMissionModelException {
    // Fail with 404 rather than 500 if the model does not exist.
    getMissionModelById(missionModelId);
    // Here the caller DECLARES the closed world rather than being checked against it, so the only
    // thing to catch is a declaration that cannot work downstream: a name the generated TypeScript
    // typings cannot carry, or a required parameter that is not declared. Both would otherwise
    // surface later as a broken argument form or as typings that do not compile.
    final var gate = ExternalResultsGate.of(
        "registerModelTypes for mission model " + missionModelId, Map.of(), Map.of(), Set.of(), Long.MAX_VALUE);
    gate.checkDeclaredTypes(activityTypes, resourceTypes);
    gate.finish();

    final var subsystems = activityTypes.values().stream()
        .map(ActivityType::subsystem)
        .flatMap(Optional::stream)
        .distinct()
        .toList();
    this.missionModelRepository.updateActivityTypes(missionModelId, activityTypes, subsystems);
    this.missionModelRepository.updateResourceTypeSchemas(missionModelId, resourceTypes);
    this.missionModelRepository.updateModelParameters(missionModelId, parameters);
  }

  private ModelType<?, ?> loadMissionModelType(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    final var missionModelJar = this.missionModelRepository.getMissionModel(missionModelId);
    return MissionModelLoader.loadModelType(missionModelDataPath.resolve(missionModelJar.path), missionModelJar.name, missionModelJar.version);
  }

  /**
   * Load a {@link MissionModel} from the mission model repository using the mission model's default mission model configuration
   *
   * @param missionModelId The ID of the mission model in the mission model repository to load.
   * @return A {@link MissionModel} domain object allowing use of the loaded mission model.
   * @throws MissionModelLoadException If the mission model cannot be loaded -- the JAR may be invalid, or the mission model
   * it contains may not abide by the expected contract at load time.
   * @throws NoSuchMissionModelException If no mission model is known by the given ID.
   */
  private MissionModel<?> loadAndInstantiateMissionModel(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    return loadAndInstantiateMissionModel(missionModelId, untruePlanStart, SerializedValue.of(Map.of()));
  }

  /**
   * Load a {@link MissionModel} from the mission model repository.
   *
   * @param missionModelId The ID of the mission model in the mission model repository to load.
   * @param configuration The mission model configuration to load the mission model with.
   * @return A {@link MissionModel} domain object allowing use of the loaded mission model.
   * @throws MissionModelLoadException If the mission model cannot be loaded -- the JAR may be invalid, or the mission model
   * it contains may not abide by the expected contract at load time.
   * @throws NoSuchMissionModelException If no mission model is known by the given ID.
   */
  private MissionModel<?> loadAndInstantiateMissionModel(
      final MissionModelId missionModelId,
      final Instant planStart,
      final SerializedValue configuration)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    final var missionModelJar = this.missionModelRepository.getMissionModel(missionModelId);
    return MissionModelLoader.loadMissionModel(
        planStart,
        configuration,
        missionModelDataPath.resolve(missionModelJar.path),
        missionModelJar.name,
        missionModelJar.version);
  }
}

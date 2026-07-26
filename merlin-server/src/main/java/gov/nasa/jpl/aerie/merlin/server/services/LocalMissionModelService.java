package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.models.MissionModelJar;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

  private static final String MODEL_TYPE_EXTERNAL = "external";

  private final Path missionModelDataPath;
  private final MissionModelRepository missionModelRepository;
  private final Instant untruePlanStart;
  /** Trusted, operator-configured external backends. External models name one of these; merlin resolves
   *  the name to a URL here, so backend URLs never come from user-supplied data. */
  private final ExternalModelBackends externalModelBackends;

  public LocalMissionModelService(
      final Path missionModelDataPath,
      final MissionModelRepository missionModelRepository,
      final Instant untruePlanStart,
      final ExternalModelBackends externalModelBackends
  ) {
    this.missionModelDataPath = missionModelDataPath;
    this.missionModelRepository = missionModelRepository;
    this.untruePlanStart = untruePlanStart;
    this.externalModelBackends = externalModelBackends;
  }

  @Override
  public Map<MissionModelId, MissionModelJar> getMissionModels() {
    return this.missionModelRepository.getAllMissionModels();
  }

  @Override
  public MissionModelJar getMissionModelById(final MissionModelId missionModelId) throws NoSuchMissionModelException {
    try {
      return this.missionModelRepository.getMissionModel(missionModelId);
    } catch (MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  /**
   * True when the model is a foreign ("external") backend rather than a Java JAR.
   * External models have no MerlinPlugin/JAR and must never be JAR-loaded; their
   * activity/model/resource metadata is pushed directly into the repository.
   */
  private boolean isExternalModel(final MissionModelId missionModelId)
  throws NoSuchMissionModelException {
    return MODEL_TYPE_EXTERNAL.equals(getMissionModelById(missionModelId).modelType);
  }

  /**
   * Resolve an external model's {@code /simulate} URL from its backend *reference* (backend name + model
   * key) against the trusted config. Returns {@code null} if the backend is unknown/unconfigured or the
   * model has no key — never from a user-supplied URL. The model key is carried as {@code ?model=<key>} so
   * one backend can serve several models (the validate URL is derived from this, preserving the query).
   */
  private String externalSimulateUrl(final MissionModelJar model) {
    final var baseUrl = this.externalModelBackends.url(model.externalBackend).orElse(null);
    if (baseUrl == null || model.externalModelKey == null || model.externalModelKey.isBlank()) return null;
    return baseUrl + (baseUrl.endsWith("/") ? "" : "/")
        + "simulate?model=" + URLEncoder.encode(model.externalModelKey, StandardCharsets.UTF_8);
  }

  /**
   * Pull an external model's activity/resource/config types from its backend by resolving the backend
   * reference to a URL and calling {@code GET /introspect?model=<key>}. Invoked by the {@code refresh*}
   * handlers when the Hasura event trigger fires on a newly-inserted external model (the same lifecycle
   * that class-loads a JAR), so a model created via an ordinary insert gets populated without merlin
   * having to insert the row itself.
   */
  private ExternalModelDiscovery.Introspection introspectExternal(final MissionModelId missionModelId)
  throws NoSuchMissionModelException {
    final var model = getMissionModelById(missionModelId);
    final var baseUrl = this.externalModelBackends.url(model.externalBackend).orElse(null);
    if (baseUrl == null) {
      throw new RuntimeException(
          "External model %s references backend '%s', which is not declared in EXTERNAL_MODEL_BACKENDS."
              .formatted(missionModelId, model.externalBackend));
    }
    final ExternalModelDiscovery.Introspection introspection;
    try {
      introspection = ExternalModelDiscovery.introspect(baseUrl, model.externalModelKey);
    } catch (final IOException | InterruptedException ex) {
      throw new RuntimeException(
          "Failed to introspect external model %s from backend '%s'".formatted(missionModelId, model.externalBackend), ex);
    }
    // Attest what we just read the types from. All three refresh* handlers introspect, but the write is
    // conditional on the value actually differing, so the first one records it and the others are no-ops
    // -- important, because any write here bumps the model revision.
    if (introspection.identityHash() != null && !introspection.identityHash().isBlank()) {
      if (this.missionModelRepository.updateExternalIdentityHash(missionModelId, introspection.identityHash())) {
        log.info("External model {} now attested to backend '{}' identity {}; model revision bumped.",
            missionModelId, model.externalBackend, introspection.identityHash());
      }
    }
    return introspection;
  }

  /**
   * Refuse to simulate when the backend no longer serves the model this row was registered against.
   *
   * <p>The stored activity_type and resource_type rows -- what the plan editor validated against, what
   * constraints are written against, what the generated typings describe -- were derived from one
   * introspection of one deployment. An adapter is redeployable, and nothing else in the system notices
   * when it comes back declaring a different type surface. Simulating anyway would produce results shaped
   * by a model that no longer matches its own metadata, and they would look entirely normal.
   *
   * <p>This is a hard stop rather than a warning because, unlike the ingest gate, there is a correct and
   * cheap remedy: re-introspect, which rewrites the stored types and bumps the revision. Note the hash
   * covers the declared INTERFACE only, so a behavior-only change to a model does not trip this -- it
   * catches "the stored types are wrong", not "the answers changed".
   */
  private void checkExternalIdentity(final MissionModelJar model, final MissionModelId missionModelId) {
    final var attested = model.externalIdentityHash;
    if (attested == null || attested.isBlank()) return;   // registered before we recorded identity
    final var baseUrl = this.externalModelBackends.url(model.externalBackend).orElse(null);
    if (baseUrl == null) return;                          // the caller already reports an unresolvable backend
    final String current;
    try {
      current = ExternalModelDiscovery.introspect(baseUrl, model.externalModelKey).identityHash();
    } catch (final Exception ex) {
      // A backend that cannot be introspected is about to fail the simulation on its own terms, with a
      // better message than anything we could produce here.
      log.warn("Could not verify identity of external model {} before simulating ({})", missionModelId, ex.toString());
      return;
    }
    if (current == null || current.isBlank() || current.equals(attested)) return;
    throw new RuntimeException(
        ("External mission model %s was registered against backend '%s' model '%s' with identity %s, but that "
         + "backend now reports identity %s. Its activity types, parameters, or resource schemas have changed, "
         + "so the types stored for this model no longer describe what would run. Re-introspect to pick up the "
         + "new types -- invoke the refreshActivityTypes / refreshResourceTypes / refreshModelParameters event "
         + "triggers manually -- or register the changed model as a new mission model to keep existing plans "
         + "pinned to the version they were built against.")
            .formatted(missionModelId, model.externalBackend, model.externalModelKey, attested, current));
  }

  /**
   * Build the closed world an external backend's results are checked against: the activity and resource
   * types the model registered, and the directive ids we are about to send it. A JAR model needs no such
   * check -- the object that declares a type is the object that produces its output -- but an external
   * backend is a separately-versioned process whose results nothing else re-validates before they reach
   * {@code span} and {@code profile_segment}.
   */
  private ExternalResultsGate externalResultsGate(final Plan plan) throws NoSuchMissionModelException {
    final var missionModelId = plan.missionModelId();
    try {
      return ExternalResultsGate.of(
          "mission model " + missionModelId,
          this.missionModelRepository.getActivityTypes(missionModelId),
          this.missionModelRepository.getResourceTypes(missionModelId),
          plan.activityDirectives().keySet().stream().map(ActivityDirectiveId::id).collect(Collectors.toSet()),
          plan.simulationDuration().in(Duration.MICROSECONDS));
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  /**
   * Best-effort validation of arguments against stored parameter metadata (no JAR).
   * Presence checks only: flags unrecognized argument names and missing required parameters.
   * Deliberately does NOT deep-type-check SerializedValue against ValueSchema, to avoid false
   * positives from a stored schema that may lag the external backend.
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
   * Ask the external backend to authoritatively validate the given activities (arguments + the model's
   * own construction/validation logic). Returns {@code null} to signal the caller should fall back to the
   * shallow stored-parameter check — when the model has no backend URL, or the backend is unreachable.
   * Validation is on the interactive editing path, so a down backend must degrade, not hard-fail.
   */
  private List<ExternalValidationBackend.ActivityValidation> tryExternalValidate(
      final MissionModelId missionModelId, final List<SerializedActivity> activities, final boolean effectiveOnly)
  {
    try {
      final var url = externalSimulateUrl(getMissionModelById(missionModelId));
      if (url == null) return null;
      return ExternalValidationBackend.validateActivities(url, activities, effectiveOnly);
    } catch (final Exception ex) {
      log.warn("External validation backend unavailable for model {}; falling back to stored-schema check ({})",
          missionModelId, ex.toString());
      return null;
    }
  }

  @Override
  public Map<String, ValueSchema> getResourceSchemas(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    if (isExternalModel(missionModelId)) {
      // External models have no JAR: resource schemas are served from the stored resource_type table.
      try {
        return this.missionModelRepository.getResourceTypes(missionModelId);
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
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
    try {
      return missionModelRepository.getActivityTypes(missionModelId);
    } catch (MissionModelRepository.NoSuchMissionModelException e) {
      throw new NoSuchMissionModelException(missionModelId, e);
    }
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
    if (isExternalModel(missionModelId)) {
      final var activityType = getActivityTypes(missionModelId).get(activity.getTypeName());
      if (activityType == null) return List.of(new ValidationNotice(List.of(), "unknown activity type"));
      // Cheap presence/required check first — catches an incomplete arg set (e.g. a form just opened with a
      // required field still empty) with a clean notice, and without a backend call that would fail to construct.
      final var presence = validateAgainstStoredParameters(
          activityType.parameters(), activityType.requiredParameters(), activity.getArguments());
      if (!presence.isEmpty()) return presence;
      // Required args present: delegate a deep, authoritative check to the model (catches e.g. a bad map value).
      final var wire = tryExternalValidate(missionModelId, List.of(activity), false);
      if (wire != null && !wire.isEmpty()) return wire.get(0).notices();
      return List.of(); // backend unreachable but presence passed: accept
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
    final Map<String, ActivityType> externalActivityTypes;
    try {
      externalActivityTypes = isExternalModel(missionModelId) ? getActivityTypes(missionModelId) : null;
    } catch (final NoSuchMissionModelException e) {
      return activities.stream()
          .<BulkArgumentValidationResponse>map(directive -> new BulkArgumentValidationResponse.NoSuchMissionModelError(e))
          .collect(Collectors.toList());
    }
    if (externalActivityTypes != null) {
      final var responses = new BulkArgumentValidationResponse[activities.size()];
      // Presence/required check per directive first; only directives with a complete arg set are sent to the
      // backend for the deep construction check (Blackbird can't construct partial args). This keeps a
      // just-opened form's "missing required parameter" clean and avoids a doomed backend call.
      final var deep = new ArrayList<SerializedActivity>();
      final var deepIndex = new ArrayList<Integer>();
      for (int i = 0; i < activities.size(); i++) {
        final var directive = activities.get(i);
        final var typeName = directive.activity().getTypeName();
        final var activityType = externalActivityTypes.get(typeName);
        if (activityType == null) {
          responses[i] = new BulkArgumentValidationResponse.NoSuchActivityError(new NoSuchActivityTypeException(typeName));
          continue;
        }
        final var presence = validateAgainstStoredParameters(
            activityType.parameters(), activityType.requiredParameters(), directive.activity().getArguments());
        if (!presence.isEmpty()) {
          responses[i] = new BulkArgumentValidationResponse.Validation(presence);
        } else {
          deepIndex.add(i);
          deep.add(directive.activity());
        }
      }
      final var wire = deep.isEmpty() ? null : tryExternalValidate(missionModelId, deep, false);
      for (int k = 0; k < deepIndex.size(); k++) {
        final int i = deepIndex.get(k);
        // Backend down but presence already passed: accept (can't deep-validate).
        final List<ValidationNotice> notices = (wire != null) ? wire.get(k).notices() : List.of();
        responses[i] = notices.isEmpty()
            ? new BulkArgumentValidationResponse.Success()
            : new BulkArgumentValidationResponse.Validation(notices);
      }
      return java.util.Arrays.asList(responses);
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
    if (isExternalModel(missionModelId)) {
      final var activityTypes = getActivityTypes(missionModelId);
      final var externalFailures = new HashMap<ActivityDirectiveId, ActivityInstantiationFailure>();
      for (final var entry : activities.entrySet()) {
        final var typeName = entry.getValue().getTypeName();
        if (!activityTypes.containsKey(typeName)) {
          externalFailures.put(entry.getKey(),
              new ActivityInstantiationFailure.NoSuchActivityType(new NoSuchActivityTypeException(typeName)));
        }
      }
      return externalFailures;
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
      if (isExternalModel(missionModelId)) {
        final var activityTypes = getActivityTypes(missionModelId);
        // Ask the backend to resolve defaults (effectiveOnly: no construction, so partial args don't fail).
        final var wire = tryExternalValidate(missionModelId, serializedActivities, true);
        final var externalResponse = new ArrayList<BulkEffectiveArgumentResponse>();
        for (int i = 0; i < serializedActivities.size(); i++) {
          final var activity = serializedActivities.get(i);
          final var typeName = activity.getTypeName();
          if (!activityTypes.containsKey(typeName)) {
            externalResponse.add(new BulkEffectiveArgumentResponse.TypeFailure(new NoSuchActivityTypeException(typeName)));
          } else if (wire != null && wire.get(i).effectiveArguments().isPresent()) {
            externalResponse.add(new BulkEffectiveArgumentResponse.Success(
                new SerializedActivity(typeName, wire.get(i).effectiveArguments().get())));
          } else {
            // Backend down or no defaults resolved: echo provided args.
            externalResponse.add(new BulkEffectiveArgumentResponse.Success(
                new SerializedActivity(typeName, activity.getArguments())));
          }
        }
        return externalResponse;
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
    if (isExternalModel(missionModelId)) {
      final List<Parameter> parameters;
      try {
        parameters = this.missionModelRepository.getModelParameters(missionModelId);
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
      // If stored config params are not yet populated, skip validation rather than flag all args as unknown.
      if (parameters.isEmpty()) return List.of();
      // Model config requiredness is not stored, so only the unrecognized-name check applies.
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
    if (isExternalModel(missionModelId)) {
      try {
        return this.missionModelRepository.getModelParameters(missionModelId);
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
    }
    return this.loadMissionModelType(missionModelId).getConfigurationType().getParameters();
  }

  @Override
  public Map<String, SerializedValue> getModelEffectiveArguments(final MissionModelId missionModelId, final Map<String, SerializedValue> arguments)
  throws NoSuchMissionModelException,
         MissionModelLoadException,
         InstantiationException
  {
    if (isExternalModel(missionModelId)) return arguments; // no stored defaults; echo provided args
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
  throws NoSuchMissionModelException
  {
    if (isExternalModel(plan.missionModelId())) {
      // External models are simulated by their external backend (e.g. the Blackbird adapter service):
      // Merlin sends the plan's directives + config there and ingests the returned results through the
      // normal resource-manager + succeedWith persistence path.
      final var model = getMissionModelById(plan.missionModelId());
      final var backendUrl = externalSimulateUrl(model);
      if (backendUrl == null) {
        throw new IllegalStateException(
            "External mission model `%s` references backend '%s', which is not declared in EXTERNAL_MODEL_BACKENDS (or has no model key)."
                .formatted(plan.missionModelId(), model.externalBackend));
      }
      checkExternalIdentity(model, plan.missionModelId());
      return ExternalSimulationBackend.simulate(
          backendUrl, plan, resourceManager, canceledListener, externalResultsGate(plan));
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
  throws NoSuchMissionModelException
  {
    if (isExternalModel(missionModelId)) {
      // External: no JAR to load; pull config parameters from the backend's /introspect over the wire.
      try {
        this.missionModelRepository.updateModelParameters(missionModelId, introspectExternal(missionModelId).parameters());
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
      return;
    }
    try {
      this.missionModelRepository.updateModelParameters(missionModelId, getModelParameters(missionModelId));
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  @Override
  public void refreshActivityTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException
  {
    if (isExternalModel(missionModelId)) {
      // External: no JAR to load; pull activity types from the backend's /introspect over the wire.
      try {
        final var activityTypes = introspectExternal(missionModelId).activityTypes();
        final var subsystems = activityTypes.values().stream()
            .map(ActivityType::subsystem).flatMap(Optional::stream).distinct().toList();
        this.missionModelRepository.updateActivityTypes(missionModelId, activityTypes, subsystems);
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
      return;
    }
    try {
      final var modelType = this.loadMissionModelType(missionModelId);
      final var registry = DirectiveTypeRegistry.extract(modelType);
      final var activityTypes = new HashMap<String, ActivityType>();
      registry.directiveTypes().forEach((name, directiveType) -> {
        final var inputType = directiveType.getInputType();
        final var outputType = directiveType.getOutputType();
        activityTypes.put(name, new ActivityType(
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
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  @Override
  public void refreshResourceTypes(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException {
    if (isExternalModel(missionModelId)) {
      // External: no JAR to instantiate; pull resource schemas from the backend's /introspect over the wire.
      try {
        this.missionModelRepository.updateResourceTypeSchemas(missionModelId, introspectExternal(missionModelId).resourceTypes());
      } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
        throw new NoSuchMissionModelException(missionModelId, ex);
      }
      return;
    }
    try {
      final var model = this.loadAndInstantiateMissionModel(missionModelId);
      this.missionModelRepository.updateResourceTypes(missionModelId, model.getResources());
    } catch (MissionModelRepository.NoSuchMissionModelException e) {
      throw new NoSuchMissionModelException(missionModelId);
    }
  }

  @Override
  public void registerModelTypes(
      final MissionModelId missionModelId,
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resourceTypes,
      final List<Parameter> parameters
  ) throws NoSuchMissionModelException {
    try {
      // Fail fast with 404 (not 500) if the model does not exist.
      getMissionModelById(missionModelId);
      // Here the adapter is DECLARING the closed world rather than being checked against it, so all we
      // can catch is a declaration that will not work downstream: a name SQL/Hasura/TS cannot carry, or a
      // required parameter that does not exist. Both would otherwise surface as a broken UI form.
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
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    }
  }

  private ModelType<?, ?> loadMissionModelType(final MissionModelId missionModelId)
  throws NoSuchMissionModelException, MissionModelLoadException
  {
    try {
      final var missionModelJar = this.missionModelRepository.getMissionModel(missionModelId);
      return MissionModelLoader.loadModelType(missionModelDataPath.resolve(missionModelJar.path), missionModelJar.name, missionModelJar.version);
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    } catch (final MissionModelLoader.MissionModelLoadException ex) {
      throw new MissionModelLoadException(ex);
    }
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
    try {
      final var missionModelJar = this.missionModelRepository.getMissionModel(missionModelId);
      return MissionModelLoader.loadMissionModel(
          planStart,
          configuration,
          missionModelDataPath.resolve(missionModelJar.path),
          missionModelJar.name,
          missionModelJar.version);
    } catch (final MissionModelRepository.NoSuchMissionModelException ex) {
      throw new NoSuchMissionModelException(missionModelId, ex);
    } catch (final MissionModelLoader.MissionModelLoadException ex) {
      throw new MissionModelLoadException(ex);
    }
  }

  public static class MissionModelLoadException extends RuntimeException {
    public MissionModelLoadException(final Throwable cause) { super(cause); }
  }
}

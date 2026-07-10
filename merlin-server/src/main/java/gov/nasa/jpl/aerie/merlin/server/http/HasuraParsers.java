package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraAction;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraMissionModelEvent;

import java.util.Optional;

import static gov.nasa.jpl.aerie.json.BasicParsers.boolP;
import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.mapP;
import static gov.nasa.jpl.aerie.json.BasicParsers.nullableP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.datasetIdP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.missionModelIdP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.planIdP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.simulationDatasetIdP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.timestampP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.profileSetP;
import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.durationP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

public abstract class HasuraParsers {
  private HasuraParsers() {}

  private static final JsonParser<HasuraAction.Session> hasuraActionSessionP
      = productP
      .field("x-hasura-role", stringP)
      .optionalField("x-hasura-user-id", stringP)
      .map(
          untuple((role, userId) -> new HasuraAction.Session(role, userId.orElse(null))),
          $ -> tuple($.hasuraRole(), Optional.ofNullable($.hasuraUserId())));

  private static <I extends HasuraAction.Input> JsonParser<HasuraAction<I>> hasuraActionF(final JsonParser<I> inputP) {
    return productP
        .field("action", productP.field("name", stringP))
        .field("input", inputP)
        .field("session_variables", hasuraActionSessionP)
        .field("request_query", stringP)
        .map(
            untuple((name, input, session, requestQuery) -> new HasuraAction<>(name, input, session)),
            $ -> tuple($.name(), $.input(), $.session(), ""));
  }

  public static final JsonParser<HasuraAction<HasuraAction.MissionModelInput>> hasuraMissionModelActionP
      = hasuraActionF(productP
                          .field("missionModelId", missionModelIdP)
                          .map(HasuraAction.MissionModelInput::new, HasuraAction.MissionModelInput::missionModelId));

  public static final JsonParser<HasuraAction<HasuraAction.PlanInput>> hasuraPlanActionP
      = hasuraActionF(productP
                          .field("planId", planIdP)
                          .map(HasuraAction.PlanInput::new, HasuraAction.PlanInput::planId));

  public static final JsonParser<HasuraAction<HasuraAction.SimulateInput>> hasuraSimulateActionP
      = hasuraActionF(
          productP
              .field("planId", planIdP)
              .optionalField("force", nullableP(boolP))
              .map(
                  untuple((planId, force) -> new HasuraAction.SimulateInput(planId, force.flatMap($ -> $))),
                  $ -> tuple($.planId(), Optional.of($.force()))
              )
  );

  public static JsonParser<HasuraAction<HasuraAction.ConstraintArguments>> constraintArgumentsP() {
    return hasuraActionF(
            productP
                .field("arguments", listP(constraintArgumentsItemP())
                .map(
                    untuple(HasuraAction.ConstraintArguments::new),
                    constraintArguments -> tuple(constraintArguments.items()))));
  }

  public static JsonParser<HasuraAction.ConstraintArgumentItem> constraintArgumentsItemP() {
    return productP
        .field("id", longP)
        .field("revision", longP)
        .field("arguments", mapP(serializedValueP))
        .map(
           untuple(HasuraAction.ConstraintArgumentItem::new),
           constraintArgumentItem -> tuple(constraintArgumentItem.constraintId(), constraintArgumentItem.revision(), constraintArgumentItem.arguments()));
  }


  public static final JsonParser<HasuraAction<HasuraAction.ConstraintViolationsInput>> hasuraConstraintsViolationsActionP
      = hasuraActionF(
      productP
          .field("planId", planIdP)
          .optionalField("simulationDatasetId", nullableP(simulationDatasetIdP))
          .optionalField("force", nullableP(boolP))
          .map(
              untuple((planId, simulationDatasetId, force) -> new HasuraAction.ConstraintViolationsInput(
                  planId,
                  simulationDatasetId.flatMap($ -> $),
                  force.flatMap($ -> $))),
              $ -> tuple($.planId(), Optional.ofNullable($.simulationDatasetId()), Optional.ofNullable($.force()))
          )
  );

  public static final JsonParser<HasuraAction<HasuraAction.ConstraintsInput>> hasuraConstraintsCodeAction
      = hasuraActionF(
          productP
              .field("missionModelId", missionModelIdP)
              .optionalField("planId", nullableP(planIdP))
              .map(
                  untuple((modelId, planId) -> new HasuraAction.ConstraintsInput(modelId, planId.flatMap($ -> $))),
                  $ -> tuple($.missionModelId(), Optional.of($.planId()))
              )
      );

   public static final JsonParser<HasuraAction.NewConstraintRevisionEvent> hasuraNewConstraintRevisionEventTriggerP
      = productP
      .field("event", productP
          .field("data", productP
              .field("new", productP
                  .field("constraint_id", longP)
                  .field("revision", longP)
                  .rest())
              .rest())
          .rest())
      .rest()
      .map(
          untuple(HasuraAction.NewConstraintRevisionEvent::new),
          $ -> tuple($.constraintId(), $.revision()));

  public static final JsonParser<HasuraMissionModelEvent> hasuraMissionModelEventTriggerP
      = productP
      .field("event", productP
          .field("data", productP
              .field("new", productP
                  .field("id", missionModelIdP)
                  .rest())
              .rest())
          .rest())
      .rest()
      .map(
          untuple(HasuraMissionModelEvent::new),
          $ -> tuple($.missionModelId()));

  private static final JsonParser<HasuraAction.MissionModelArgumentsInput> hasuraMissionModelArgumentsInputP
      = productP
      .field("missionModelId", missionModelIdP)
      .field("modelArguments", mapP(serializedValueP))
      .map(
          untuple(HasuraAction.MissionModelArgumentsInput::new),
          $ -> tuple($.missionModelId(), $.arguments()));

  public static final JsonParser<HasuraAction<HasuraAction.MissionModelArgumentsInput>> hasuraMissionModelArgumentsActionP
      = hasuraActionF(hasuraMissionModelArgumentsInputP);

  private static final JsonParser<HasuraAction.ActivityInput> hasuraActivityInputP
      = productP
      .field("missionModelId", missionModelIdP)
      .field("activityTypeName", stringP)
      .field("activityArguments", mapP(serializedValueP))
      .map(
          untuple(HasuraAction.ActivityInput::new),
          $ -> tuple($.missionModelId(), $.activityTypeName(), $.arguments()));

  private static final JsonParser<SerializedActivity> hasuraActivityBulkItemP
      = productP
      .field("activityTypeName", stringP)
      .field("activityArguments", mapP(serializedValueP))
      .map(
          untuple(SerializedActivity::new),
          $ -> tuple($.getTypeName(), $.getArguments()));

  public static final JsonParser<HasuraAction<HasuraAction.ActivityBulkInput>> hasuraActivityBulkActionP
      = hasuraActionF(
          productP
              .field("missionModelId", missionModelIdP)
              .field("activities", listP(hasuraActivityBulkItemP))
              .map(
                  untuple(HasuraAction.ActivityBulkInput::new),
                  $ -> tuple($.missionModelId(), $.activities())));

  public static final JsonParser<HasuraAction<HasuraAction.ActivityInput>> hasuraActivityActionP
      = hasuraActionF(hasuraActivityInputP);

  public static final JsonParser<HasuraAction<HasuraAction.UploadExternalDatasetInput>> hasuraUploadExternalDatasetActionP
      = hasuraActionF(
          productP
            .field("planId", planIdP)
            .optionalField("simulationDatasetId", nullableP(simulationDatasetIdP))
            .field("datasetStart", timestampP)
            .field("profileSet", profileSetP)
            .map(
                untuple((planId, simulationDatasetId, datasetStart, profileSet) -> new HasuraAction.UploadExternalDatasetInput(
                    planId,
                    simulationDatasetId.flatMap($ -> $),
                    datasetStart,
                    profileSet)),
                (HasuraAction.UploadExternalDatasetInput $) -> tuple(
                    $.planId(),
                    Optional.of($.simulationDatasetId()),
                    $.datasetStart(),
                    $.profileSet())));

  public static final JsonParser<HasuraAction<HasuraAction.ExtendExternalDatasetInput>> hasuraExtendExternalDatasetActionP
      = hasuraActionF(
          productP
            .field("datasetId", datasetIdP)
            .field("profileSet", profileSetP)
            .map(
                untuple(HasuraAction.ExtendExternalDatasetInput::new),
                $ -> tuple($.datasetId(), $.profileSet())));

  // --- Foreign ("external") model backend ---

  private static final JsonParser<Parameter> modelParameterP =
      productP
          .field("name", stringP)
          .field("schema", valueSchemaP)
          .map(
              untuple(Parameter::new),
              $ -> tuple($.name(), $.schema()));

  private static final JsonParser<HasuraAction.ModelResourceType> modelResourceTypeP =
      productP
          .field("name", stringP)
          .field("schema", valueSchemaP)
          .map(
              untuple(HasuraAction.ModelResourceType::new),
              $ -> tuple($.name(), $.schema()));

  private static final JsonParser<ActivityType> modelActivityTypeP =
      productP
          .field("name", stringP)
          .field("parameters", listP(modelParameterP))
          .field("requiredParameters", listP(stringP))
          .field("computedAttributesSchema", valueSchemaP)
          .optionalField("subsystem", stringP)
          .optionalField("description", stringP)
          .map(
              untuple((name, parameters, required, computed, subsystem, description) ->
                  new ActivityType(name, parameters, required, computed, subsystem, description)),
              $ -> tuple($.name(), $.parameters(), $.requiredParameters(),
                         $.computedAttributesValueSchema(), $.subsystem(), $.description()));

  public static final JsonParser<HasuraAction<HasuraAction.RegisterModelTypesInput>> hasuraRegisterModelTypesActionP =
      hasuraActionF(
          productP
              .field("missionModelId", missionModelIdP)
              .field("activityTypes", listP(modelActivityTypeP))
              .field("resourceTypes", listP(modelResourceTypeP))
              .field("parameters", listP(modelParameterP))
              .map(
                  untuple((missionModelId, activityTypes, resourceTypes, parameters) ->
                      new HasuraAction.RegisterModelTypesInput(missionModelId, activityTypes, resourceTypes, parameters)),
                  $ -> tuple($.missionModelId(), $.activityTypes(), $.resourceTypes(), $.parameters())));

  private static final JsonParser<gov.nasa.jpl.aerie.merlin.server.models.ExternalSpan> externalSpanP =
      productP
          .field("spanId", longP)
          .optionalField("parentId", longP)
          .field("type", stringP)
          .field("startOffset", durationP)
          .optionalField("duration", durationP)
          .optionalField("directiveId", longP)
          .field("arguments", mapP(serializedValueP))
          .optionalField("computedAttributes", serializedValueP)
          .map(
              untuple((spanId, parentId, type, startOffset, duration, directiveId, arguments, computedAttributes) ->
                  new gov.nasa.jpl.aerie.merlin.server.models.ExternalSpan(
                      spanId, parentId, type, startOffset, duration, directiveId, arguments, computedAttributes)),
              $ -> tuple($.spanId(), $.parentId(), $.type(), $.startOffset(), $.duration(),
                         $.directiveId(), $.arguments(), $.computedAttributes()));

  private static final JsonParser<HasuraAction.ExternalSimulationResults> externalSimulationResultsP =
      productP
          .field("startTime", timestampP)
          .field("duration", durationP)
          .field("profiles", profileSetP)
          .field("spans", listP(externalSpanP))
          .map(
              untuple((startTime, duration, profiles, spans) ->
                  new HasuraAction.ExternalSimulationResults(startTime, duration, profiles, spans)),
              $ -> tuple($.startTime(), $.duration(), $.profiles(), $.spans()));

  public static final JsonParser<HasuraAction<HasuraAction.IngestExternalSimulationResultsInput>> hasuraIngestExternalSimulationResultsActionP =
      hasuraActionF(
          productP
              .field("planId", planIdP)
              .optionalField("simulationId", nullableP(longP))
              .field("results", externalSimulationResultsP)
              .map(
                  untuple((planId, simulationId, results) ->
                      new HasuraAction.IngestExternalSimulationResultsInput(planId, simulationId.flatMap($ -> $), results)),
                  $ -> tuple($.planId(), Optional.of($.simulationId()), $.results())));
}

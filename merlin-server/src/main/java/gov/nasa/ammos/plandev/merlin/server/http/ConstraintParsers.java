package gov.nasa.ammos.plandev.merlin.server.http;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.merlin.server.models.ProceduralConstraintResult;

import java.util.List;

import static gov.nasa.ammos.plandev.constraints.json.ConstraintParsers.intervalP;
import static gov.nasa.ammos.plandev.constraints.json.ConstraintParsers.violationP;
import static gov.nasa.ammos.plandev.json.BasicParsers.listP;
import static gov.nasa.ammos.plandev.json.BasicParsers.longP;
import static gov.nasa.ammos.plandev.json.BasicParsers.productP;
import static gov.nasa.ammos.plandev.json.BasicParsers.stringP;
import static gov.nasa.ammos.plandev.json.Uncurry.tuple;
import static gov.nasa.ammos.plandev.json.Uncurry.untuple;

public final class ConstraintParsers {
  public static final JsonParser<ProceduralConstraintResult> proceduralConstraintResultP =
      productP
          .field("violations", listP(violationP))
          .field("gaps", listP(intervalP))
          .field("resourceIds", listP(stringP))
          .field("constraintId", longP)
          .field("constraintRevision", longP)
          .field("constraintName", stringP)
          .map(
              untuple((violations, gaps, resourceNames, constraintId, constraintRevision, constraintName) -> new ProceduralConstraintResult(
                  violations,
                  constraintId,
                  constraintRevision,
                  constraintName)),
              $ -> tuple($.violations(), List.of(), List.of(), $.constraintId(), $.constraintRevision(), $.constraintName())
          );
}

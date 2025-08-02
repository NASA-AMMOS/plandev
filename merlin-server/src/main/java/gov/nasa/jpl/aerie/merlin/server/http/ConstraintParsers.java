package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.server.models.ProceduralConstraintResult;

import java.util.List;

import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.intervalP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.violationP;
import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;

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

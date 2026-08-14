package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.json.JsonParser;

import java.util.List;

import static gov.nasa.jpl.aerie.json.BasicParsers.intP;
import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;

public class SchedulingCompilationError {
  private static final JsonParser<CodeLocation> codeLocationP =
      productP
          .field("line", intP)
          .field("column", intP)
          .map(
              untuple(CodeLocation::new),
              $ -> tuple($.line, $.column));

  private static final JsonParser<UserCodeError> userCodeErrorP =
      productP
          .field("message", stringP)
          .field("stack", stringP)
          .field("location", codeLocationP)
          .field("completeStack", stringP)
          .map(
              untuple(UserCodeError::new),
              $ -> tuple($.message, $.stack, $.location, $.completeStack));

  public static final JsonParser<List<UserCodeError>> schedulingErrorJsonP = listP(userCodeErrorP);

  public record CodeLocation(Integer line, Integer column) {}

  public record UserCodeError(String message, String stack, CodeLocation location, String completeStack) {}
}

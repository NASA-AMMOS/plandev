package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.json.JsonParser;

import java.util.List;

import static gov.nasa.jpl.aerie.json.BasicParsers.*;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;

public class ConstraintsCompilationError extends Exception {
  private static final JsonParser<CodeLocation> codeLocationP =
      productP
          .field("line", intP)
          .field("column", intP)
          .map(
              untuple(CodeLocation::new),
              $ -> tuple($.line, $.column));

  private static final JsonParser<ConstraintsCompilationError> userCodeErrorP =
      productP
          .field("message", stringP)
          .field("stack", stringP)
          .field("location", codeLocationP)
          .field("completeStack", stringP)
          .map(
              untuple(ConstraintsCompilationError::new),
              $ -> tuple($.message, $.stack, $.location, $.completeStack));

  public static final JsonParser<List<ConstraintsCompilationError>> constraintsErrorJsonP = listP(userCodeErrorP);

  public record CodeLocation(Integer line, Integer column) {}

  private String message;
  private final String stack;
  private final CodeLocation location;
  private final String completeStack;

  public ConstraintsCompilationError(String message, String stack, CodeLocation location, String completeStack) {
    super(message);
    this.message = message;
    this.stack = stack;
    this.location = location;
    this.completeStack = completeStack;
  }

  @Override
  public String getMessage() { return message; }
  public String getStack() { return stack; }
  public CodeLocation getLocation() { return location; }
  public String getCompleteStack() { return completeStack; }

  public void prependMessage(String m) {
    message = m + this.message;
  }
}

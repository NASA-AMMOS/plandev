package gov.nasa.jpl.aerie.scheduler.server.graphql;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.Unit;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

import static gov.nasa.jpl.aerie.json.BasicParsers.doubleP;
import static gov.nasa.jpl.aerie.json.BasicParsers.listP;
import static gov.nasa.jpl.aerie.json.BasicParsers.literalP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.durationP;

public final class ProfileParsers {
  public static final JsonParser<RealDynamics> realDynamicsP
      = productP
      . field("initial", doubleP)
      . field("rate", doubleP)
      . map(
          untuple(RealDynamics::linear),
          $ -> tuple($.initial, $.rate));

  public static final JsonParser<ProfileSegment<RealDynamics>> realProfileSegmentP
      = productP
      . field("start_offset", durationP)
      . field("dynamics", realDynamicsP)
      . map(
          untuple((start_offset, dynamics) -> new ProfileSegment<RealDynamics>(start_offset, dynamics)),
          $ -> tuple($.extent(), $.dynamics()));

  public static final JsonParser<ProfileSegment<SerializedValue>> discreteProfileSegmentP
      = productP
      . field("start_offset", durationP)
      . field("dynamics", serializedValueP)
      . map(
          untuple( ProfileSegment::new),
          $ -> tuple($.extent(), $.dynamics()));

  public static final JsonParser<ValueSchema> discreteValueSchemaTypeP = productP
      .field("type", literalP("discrete"))
      .field("schema", valueSchemaP)
      .map(untuple((type, schema) -> schema),
      $ -> tuple(Unit.UNIT, $));

  public static final JsonParser<ValueSchema> realValueSchemaTypeP = productP
      .field("type", literalP("real"))
      .field("schema", valueSchemaP)
      .map(untuple((type, schema) -> schema),
           $ -> tuple(Unit.UNIT, $));

  public static final JsonParser<Pair<ValueSchema, List<ProfileSegment<RealDynamics>>>> realProfileP
      = productP
      . field("name", stringP)
      . field("type", realValueSchemaTypeP)
      . field("profile_segments", listP(realProfileSegmentP))
      . map(
          untuple((name, type, segments) -> Pair.of(type, segments)),
          $ -> tuple("", $.getLeft(), $.getRight()));

  public static final JsonParser<Pair<ValueSchema, List<ProfileSegment<SerializedValue>>>> discreteProfileP
      = productP
      . field("name", stringP)
      . field("type", discreteValueSchemaTypeP)
      . field("profile_segments", listP(discreteProfileSegmentP))
      . map(
          untuple((name, type, segments) -> Pair.of(type, segments)),
          $ -> tuple("", $.getLeft(), $.getRight()));

}

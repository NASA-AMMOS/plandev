package gov.nasa.ammos.plandev.merlin.server;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.http.InvalidJsonEntityException;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.ActivityAttributesRecord;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresProfileStreamer;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresSpanStreamer;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.SpanRecord;
import gov.nasa.ammos.plandev.types.Timestamp;

import javax.json.Json;
import javax.json.stream.JsonParser;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.ammos.plandev.json.BasicParsers.intP;
import static gov.nasa.ammos.plandev.json.BasicParsers.longP;
import static gov.nasa.ammos.plandev.json.BasicParsers.productP;
import static gov.nasa.ammos.plandev.json.BasicParsers.stringP;
import static gov.nasa.ammos.plandev.json.Uncurry.tuple;
import static gov.nasa.ammos.plandev.json.Uncurry.untuple;
import static gov.nasa.ammos.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.ammos.plandev.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.ammos.plandev.merlin.server.http.MerlinParsers.durationP;
import static gov.nasa.ammos.plandev.merlin.server.http.ProfileParsers.discreteProfileSegmentP;
import static gov.nasa.ammos.plandev.merlin.server.http.ProfileParsers.realProfileSegmentP;
import static gov.nasa.ammos.plandev.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;

public class ExternalSimulationFileParser {
  private enum TopLevelState { none, spans, profiles }

  private TopLevelState currentState;
  private final Connection connection;

  public ExternalSimulationFileParser(Connection connection) {
    this.connection = connection;
    this.currentState = TopLevelState.none;
  }

  public void parse(
      Path filePath,
      long datasetId,
      Timestamp simulationStart
  ) throws IOException, InvalidJsonEntityException, SQLException {
    try(final var fileReader = new FileReader(filePath.toFile());
        final var jsonParser = Json.createParser(fileReader);
        final var spanStreamer = new PostgresSpanStreamer(connection, datasetId, simulationStart);
        final var profileStreamer = new PostgresProfileStreamer(connection, datasetId)
    ) {
      while(jsonParser.hasNext()) {
        final var curEvent = jsonParser.next();
        switch (curEvent) {
          case START_ARRAY -> {
            // Parse the contents of the spans array
            if(currentState == TopLevelState.spans) {
              parseSpansArray(jsonParser, spanStreamer, simulationStart);
            }
          }
          case START_OBJECT -> {
            // Parse the contents of the profiles object
            if(currentState == TopLevelState.profiles) {
              parseProfilesObject(jsonParser, profileStreamer);
            }
          }
          case KEY_NAME -> {
            // Set the current top-level state
            final var keyName = jsonParser.getString();
            if(currentState == TopLevelState.none) {
              currentState = TopLevelState.valueOf(keyName);
            }
          }
        }
      }
    }
  }

  private void parseSpansArray(
      JsonParser fileStream,
      final PostgresSpanStreamer streamer,
      final Timestamp simulationStart
  ) throws InvalidJsonEntityException {
    while(fileStream.hasNext()) {
      final var curEvent = fileStream.next();
      switch (curEvent) {
        case START_OBJECT -> {
          // Fetch each object as we get it
          final var span = spanP
              .parse(fileStream.getObject())
              .getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
         streamer.accept(span.spanId, span.toPGSpanRecord(simulationStart));
        }
        case END_ARRAY -> {
          currentState = TopLevelState.none;
          return;
        }
      }
    }
  }

  private void parseProfilesObject(
      JsonParser fileStream,
      PostgresProfileStreamer profileStreamer
  ) throws InvalidJsonEntityException, SQLException {
    while(fileStream.hasNext()) {
      // The profile name is stored at key name section
      if (fileStream.next() == JsonParser.Event.KEY_NAME) {
        final var profileName = fileStream.getString();
        parseProfile(fileStream, profileName, profileStreamer);
      }
    }
  }

  private void parseProfile(
      JsonParser fileStream,
      String profileName,
      PostgresProfileStreamer profileStreamer
  ) throws InvalidJsonEntityException, SQLException {
    ValueSchema schema = null;
    Profile.ProfileType type = null;

    while(fileStream.hasNext()) {
      if (fileStream.next() == JsonParser.Event.KEY_NAME) {
        switch (fileStream.getString()) {
          case "type":
            fileStream.next(); // get to the VALUE_STRING
            type = Profile.ProfileType.valueOf(fileStream.getString());
            break;
          case "schema":
            fileStream.next(); // get to the START_OBJECT
            schema = valueSchemaP
                .parse(fileStream.getObject())
                .getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
            break;
          case "segments":
            // Because we are only reading the file once
            // (as opposed to opening it once for the profiles, then a second time for the segments)
            // segments MUST be the last key in the object
            if (type == null || schema == null) {
              throw new IllegalStateException("segments for profile %s specified before type and schema information".formatted(
                  profileName));
            }
            final var profile = new Profile(profileName, type, schema);
            parseProfileSegments(fileStream, profile, profileStreamer);
            break;
        }
      }
    }
  }

  private void parseProfileSegments(
      final JsonParser fileStream,
      final Profile currentProfile,
      final PostgresProfileStreamer profileStreamer
  ) throws InvalidJsonEntityException, SQLException {
    while (fileStream.hasNext()) {
      final var curEvent = fileStream.next();
      switch (curEvent) {
        case START_OBJECT -> {
          final var segment = fileStream.getObject();

          switch (currentProfile.type){
            case real -> profileStreamer.acceptRealSegment(
                currentProfile.name,
                currentProfile.schema,
                realProfileSegmentP
                    .parse(segment)
                    .getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)))
                );
            case discrete -> profileStreamer.acceptDiscreteSegment(
                currentProfile.name,
                currentProfile.schema,
                discreteProfileSegmentP
                    .parse(segment)
                    .getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)))
            );
          }
        }
        case END_ARRAY -> {
          return;
        }
      }
    }
  }

  private record Span(
      int spanId,
      String type,
      Duration startOffset,
      Optional<Duration> duration,
      Map<String, SerializedValue> arguments,
      Optional<SerializedValue> computedAttributes,
      Optional<Long> directiveId,
      Optional<Long> parentId
  ) {
    SpanRecord toPGSpanRecord(Timestamp simStartTime) {
      return new SpanRecord(
          type,
          simStartTime.plusMicros(startOffset.micros()).toInstant(),
          duration,
          parentId,
          List.of(), // childIds is used when reading spans not when posting them
          new ActivityAttributesRecord(
              directiveId,
              arguments,
              computedAttributes
          )
      );
    }
  }

  private final static gov.nasa.ammos.plandev.json.JsonParser<Span> spanP =
      productP
          .field("span_id", intP)
          .field("type", stringP)
          .field("start_offset", durationP)
          .optionalField("duration", durationP)
          .field("arguments", activityArgumentsP)
          .optionalField("computed_attributes", serializedValueP)
          .optionalField("directive_id", longP)
          .optionalField("parent_id", longP)
          .map(
              untuple(Span::new),
              span -> tuple(span.spanId, span.type, span.startOffset, span.duration,
                            span.arguments, span.computedAttributes, span.directiveId, span.parentId)
          );

  private record Profile(
      String name,
      ProfileType type,
      ValueSchema schema
  ) {
    enum ProfileType {real, discrete}
  }
}

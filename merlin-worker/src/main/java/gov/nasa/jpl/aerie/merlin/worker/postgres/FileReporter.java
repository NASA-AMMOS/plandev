package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.Reporter;
import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ActivityAttributesRecord;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityAttributesP;

public class FileReporter implements Reporter {
  BufferedWriter writer;
  private final Map<String, Long> spanIds;

  public FileReporter() {
    try {
      this.writer = new BufferedWriter(new FileWriter(
          "/usr/src/app/merlin_file_store/simulation-results/1.log",
          false));

      this.spanIds = new LinkedHashMap<>();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void report(final Message message) {
    try {
      switch (message) {
        case Message.AdvanceTime m -> {
          writeLine(Json.createObjectBuilder()
                        .add("channel", "advance_time")
                        .add(
                            "payload",
                            Json.createObjectBuilder()
                                .add("start_offset", Long.toString(m.startOffset().micros())).build()).build().toString());
        }
        case Message.DeclareProfile m -> {
          writeLine(Json.createObjectBuilder()
                        .add("channel", "declare_profile")
                        .add(
                            "payload",
                            Json.createObjectBuilder()
                                .add("profile_name", m.profileName())
                                .add("schema", valueSchemaP.unparse(m.schema()))
                                .build()).build().toString());

        }
        case Message.DeclareTopic m -> {
        }
        case Message.Error m -> {
        }
        case Message.Events m -> {
        }
        case Message.UpdateProfile m -> {
          writeLine(Json.createObjectBuilder()
                        .add("channel", "update_profile")
                        .add(
                            "payload",
                            Json.createObjectBuilder()
                                .add("start_offset", Long.toString(m.startOffset().micros()))
                                .add("profile_name", m.profileName())
                                .add("value", new SerializedValueJsonParser().unparse(m.value()))
                                .build()).build().toString());
        }
        case Message.UpdateSpan m -> {
          var spanId = this.spanIds.computeIfAbsent(m.spanId(), $ -> (long) this.spanIds.size());
          var parentSpanId = m.parentSpanId().map(s -> this.spanIds.computeIfAbsent(s, $ -> (long) this.spanIds.size()));

          final JsonObjectBuilder payload = Json.createObjectBuilder()
                                                .add("type", m.type())
                                                .add("span_id", spanId)
                                                .add("start_offset", Long.toString(m.startOffset().micros()))
                                                .add(
                                                    "attributes",
                                                    buildAttributes(
                                                        m.directiveId(),
                                                        m.payload().asMap().get(),
                                                        Optional.empty()));

          if (m.duration().isPresent()) {
            payload.add("duration", Long.toString(m.duration().get().micros()));
          } else {
            payload.add("duration", JsonValue.NULL);
          }

          if (parentSpanId.isPresent()) {
            payload.add("parent_id", m.parentSpanId().get());
          } else {
            payload.add("parent_id", JsonValue.NULL);
          }

          writeLine(Json.createObjectBuilder()
                        .add("channel", "span")
                        .add(
                            "payload",
                            payload).build().toString());
        }
        case Message.Finish m -> {
          writeLine(Json.createObjectBuilder()
                        .add("channel", "finish")
                        .add(
                            "payload",
                            Json.createObjectBuilder().build()).build().toString());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeLine(final String string) throws IOException {
    writer.write(string);
    writer.newLine();
    writer.flush();
  }

  private JsonValue buildAttributes(
      final Optional<Long> directiveId,
      final Map<String, SerializedValue> arguments,
      final Optional<SerializedValue> returnValue)
  {
    return activityAttributesP.unparse(new ActivityAttributesRecord(directiveId, arguments, returnValue));
  }

  @Override
  public void close() throws Exception {
    writer.close();
  }
}

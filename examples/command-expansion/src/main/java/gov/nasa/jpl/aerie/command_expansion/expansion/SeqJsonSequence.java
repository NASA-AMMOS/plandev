package gov.nasa.jpl.aerie.command_expansion.expansion;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

// Also a mission-specific type, but this designed to directly mirror the serialized format.
// Since we're using Seq.JSON as our sample format, I'm using Jackson to describe the format as a Java class.
// Other missions might need to do more for serialization

/**
 * Serializable representation of SEQ JSON.
 */
public record SeqJsonSequence(
        String id,
        List<SeqJsonStep> steps
) {
    public record SeqJsonStep(
            String type,
            SeqJsonStepTime time,
            String stem,
            List<SeqJsonCommandArg> args
    ) {
        public static SeqJsonStep command(SeqJsonStepTime time, String stem, List<SeqJsonCommandArg> args) {
            return new SeqJsonStep("command", time, stem, args);
        }
    }

    public record SeqJsonStepTime(
            String type,
            @JsonSerialize(using = SeqJsonStepTimeSerializer.class)
            @JsonDeserialize(using = SeqJsonStepTimeDeserializer.class)
            Object tag
    ) {
        public static SeqJsonStepTime absolute(Instant time) {
            return new SeqJsonStepTime("ABSOLUTE", time);
        }

        public static SeqJsonStepTime relative(Duration offset) {
            return new SeqJsonStepTime("RELATIVE", offset);
        }

        public static SeqJsonStepTime epochRelative(Duration offset) {
            return new SeqJsonStepTime("EPOCH_RELATIVE", offset);
        }

        public static SeqJsonStepTime commandComplete() {
            return new SeqJsonStepTime("COMMAND_COMPLETE", null);
        }
    }

    public record SeqJsonCommandArg(
            String type,
            Object value
    ) {
        public static SeqJsonCommandArg of(Object value) {
            return switch (value) {
                case Number n -> new SeqJsonCommandArg("number", n);
                case String s -> new SeqJsonCommandArg("string", s);
                case Enum<?> e -> new SeqJsonCommandArg("string", e.name());
                default -> throw new RuntimeException("Unsupported arg type: " + value.getClass().getSimpleName());
            };
        }
    }

    public String serialize() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static SeqJsonSequence deserialize(final String json) {
        try {
            return new ObjectMapper().readValue(json, SeqJsonSequence.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static final DateTimeFormatter TIME_TAG_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-DDD'T'HH:mm:ss.SSS").withZone(ZoneId.from(ZoneOffset.UTC));

    private static class SeqJsonStepTimeSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object o, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
            switch (o) {
                case null:
                    jsonGenerator.writeNull();
                    break;
                case Instant instant:
                    jsonGenerator.writeString(TIME_TAG_FORMATTER.format(instant));
                    break;
                case Duration duration:
                    jsonGenerator.writeString(hmsFormat(duration));
                    break;
                default:
                    throw new RuntimeException("Unhandled type: " + o.getClass().getSimpleName());
            }
        }

        private String hmsFormat(Duration duration) {
            long h = duration.in(HOURS);
            duration = duration.minus(h, HOURS);
            long m = duration.in(MINUTES);
            duration = duration.minus(m, MINUTES);
            double s = duration.ratioOver(SECONDS);
            return String.format("%02d:%02d:%06.3f", h, m, s);
        }
    }

    private static class SeqJsonStepTimeDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
            String serializedTag = p.getValueAsString();

            if (serializedTag == null) return null;

            var durationMatcher = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2}(?:\\.\\d*))")
                    .matcher(serializedTag);
            if (durationMatcher.find()) {
                int h = Integer.parseInt(durationMatcher.group(1));
                int m = Integer.parseInt(durationMatcher.group(2));
                double s = Double.parseDouble(durationMatcher.group(3));
                return Duration.roundNearest(s, SECONDS)
                        .plus(m, MINUTES)
                        .plus(h, HOURS);
            }

            try {
                return Instant.from(TIME_TAG_FORMATTER.parse(serializedTag));
            } catch (DateTimeParseException e) {
                throw new InvalidFormatException(
                        p, "Time tag does not look like a relative time nor an absolute time.", serializedTag, Object.class);
            }
        }
    }
}

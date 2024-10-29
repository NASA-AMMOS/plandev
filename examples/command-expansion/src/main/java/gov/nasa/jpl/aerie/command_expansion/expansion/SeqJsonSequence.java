package gov.nasa.jpl.aerie.command_expansion.expansion;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.io.IOException;
import java.time.Instant;
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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "command", value = SeqJsonCommand.class),
            @JsonSubTypes.Type(name = "ground_event", value = SeqJsonGroundEvent.class)
    })
    public sealed interface SeqJsonStep {}
    public record SeqJsonCommand(
            SeqJsonStepTime time,
            String stem,
            List<SeqJsonCommandArg> args
    ) implements SeqJsonStep {}
    public record SeqJsonGroundEvent(
            SeqJsonStepTime time,
            String name,
            List<SeqJsonCommandArg> args
    ) implements SeqJsonStep {}


    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "ABSOLUTE", value = SeqJsonAbsoluteTime.class),
            @JsonSubTypes.Type(name = "RELATIVE", value = SeqJsonRelativeTime.class),
            @JsonSubTypes.Type(name = "EPOCH_RELATIVE", value = SeqJsonEpochRelativeTime.class),
            @JsonSubTypes.Type(name = "COMMAND_COMPLETE", value = SeqJsonCommandCompleteTime.class),
    })
    public sealed interface SeqJsonStepTime {}
    public record SeqJsonAbsoluteTime(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-DDD'T'HH:mm:ss.SSS", timezone = "UTC")
            Instant tag
    ) implements SeqJsonStepTime {}
    public record SeqJsonRelativeTime(
            @JsonSerialize(using = DurationTagSerializer.class)
            @JsonDeserialize(using = DurationTagDeserializer.class)
            Duration tag
    ) implements SeqJsonStepTime {}
    public record SeqJsonEpochRelativeTime(
            @JsonSerialize(using = DurationTagSerializer.class)
            @JsonDeserialize(using = DurationTagDeserializer.class)
            Duration tag
    ) implements SeqJsonStepTime {}
    public record SeqJsonCommandCompleteTime() implements SeqJsonStepTime {}

    public static class DurationTagSerializer extends JsonSerializer<Duration> {
        @Override
        public void serialize(Duration value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
            long h = value.in(HOURS);
            value = value.minus(h, HOURS);
            long m = value.in(MINUTES);
            value = value.minus(m, MINUTES);
            double s = value.ratioOver(SECONDS);
            jsonGenerator.writeString(String.format("%02d:%02d:%06.3f", h, m, s));
        }
    }
    public static class DurationTagDeserializer extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
            var durationString = p.getValueAsString();
            var durationMatcher = Pattern.compile("^(\\d{1,2}):(\\d{1,2}):(\\d{1,2}(?:\\.\\d*)?)$")
                    .matcher(durationString);
            if (durationMatcher.find()) {
                int h = Integer.parseInt(durationMatcher.group(1));
                int m = Integer.parseInt(durationMatcher.group(2));
                double s = Double.parseDouble(durationMatcher.group(3));
                return Duration.roundNearest(s, SECONDS)
                        .plus(m, MINUTES)
                        .plus(h, HOURS);
            } else {
                throw new InvalidFormatException(
                        p,
                        "Invalid format for a Duration. Please format durations like 00:00:00.000",
                        durationString,
                        Duration.class);
            }
        }
    }

    public record SeqJsonCommandArg(
            String type,
            Object value
    ) {
        public static final String NUMBER_TYPE = "number";
        public static final String STRING_TYPE = "string";

        public static SeqJsonCommandArg of(Object value) {
            return switch (value) {
                case Number n -> new SeqJsonCommandArg(NUMBER_TYPE, n);
                case String s -> new SeqJsonCommandArg(STRING_TYPE, s);
                case Enum<?> e -> new SeqJsonCommandArg(STRING_TYPE, e.name());
                default -> throw new RuntimeException("Unsupported arg type: " + value.getClass().getSimpleName());
            };
        }
    }

    public String serialize() {
        try {
            return mapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static SeqJsonSequence deserialize(final String json) {
        try {
            return mapper().readValue(json, SeqJsonSequence.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static ObjectMapper mapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}

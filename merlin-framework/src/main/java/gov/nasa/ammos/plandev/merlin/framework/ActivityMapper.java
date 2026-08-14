package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.stream.Collectors;

public interface ActivityMapper<Model, Specification, Return>
    extends DirectiveType<Model, Specification, Return>
{
  Topic<Specification> getInputTopic();
  Topic<Return> getOutputTopic();

  default OutputType<Specification> getInputAsOutput() {
    final var inputType = this.getInputType();

    return new OutputType<>() {
      @Override
      public ValueSchema getSchema() {
        return ValueSchema.ofStruct(inputType
            .getParameters()
            .stream()
            .collect(Collectors.toMap($ -> $.name(), $ -> $.schema())));
      }

      @Override
      public SerializedValue serialize(final Specification value) {
        return SerializedValue.of(inputType.getArguments(value));
      }
    };
  }
}

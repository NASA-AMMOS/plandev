/**
 * Interfaces implemented by a mission model.
 *
 * <p> In analogy to regular Java, it can be helpful to think of the model as a kind of class, whose mutator methods are
 * described by directive types, whose getter methods are described by resources, whose constructor parameters are
 * described by a configuration type, and whose internal state is described by simulation cells. In this metaphor,
 * the interfaces implemented by the model (in the {@link gov.nasa.jpl.aerie.merlin.protocol.model} package) provide
 * <i><a href="https://en.wikipedia.org/wiki/Mirror_(programming)">reflective access</a></i> to the mission model and
 * these features thereof. While a multi-mission driver cannot know about the types used by a model concretely,
 * it can work with them generically and at a distance by way of these interfaces. </p>
 *
 * <p> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType} interface describes the mission
 * model as a whole, and is the starting point for any interaction with a model. See its documentation for details. </p>
 *
 * <p> Here are some of the most important reflective interfaces: </p>
 *
 * <ul>
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType} interface describes the mission
 *    model as a whole, and is the starting point for any interaction with a model. As a reflective interface, it is
 *    analogous to {@link java.lang.Class}. It provides access to its directive types and configuration type, as well
 *    as the ability to instantiate the underlying model type. </li>
 *
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.InputType} interface describes a type of data that can
 *    be accepted by the model. It provides facilities for constructing and validating input values. As a reflective
 *    interface, it is roughly analogous to the {@link java.lang.Class}es provided by
 *    {@link java.lang.reflect.Method#getParameterTypes()}. The model itself accepts input as configuration, and its
 *    directive types accept input to modulate the behavior of the resulting directives. </li>
 *
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.OutputType} interface describes a type of data that can
 *    be produced by the model. It provides facilities for interrogating the type's schematic structure and serializing
 *    individual values of the type. As a reflective interface, it is roughly analogous to the {@link java.lang.Class}
 *    returned by {@link java.lang.reflect.Method#getReturnType()}. The model produces such output from directives
 *    and resources. </li>
 *
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType} interface describes a directive type,
 *    a family of behaviors that the model can perform. As a reflective interface, it is roughly analogous to
 *    {@link java.lang.reflect.Method}. It includes an input type, an output type, and a facility for constructing an
 *    executable {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task} against a model instance.  </li>
 *
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.Resource} interface describes a resource, an observable
 *    value computed based on the model's internal state.  As a reflective interface, it is roughly analogous to
 *    {@link java.lang.reflect.Method}. It includes an output type and a facility for querying a value of that type
 *    from a model instance. </li>
 *
 *    <li> The {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType} interface describes the internal state of a
 *    model. It provides facilities for applying events to a cell and stepping it forward over time. As a reflective
 *    interface, it is roughly analogous to {@link java.lang.reflect.Field}. </li>
 * </ul>
 */
package gov.nasa.jpl.aerie.merlin.protocol.model;

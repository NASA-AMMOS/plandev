/**
 * A toolkit for authoring bidirectional JSON parsers with parser combinators.
 *
 * <p> This library provides the basic building blocks for defining serialization formats for any Java type,
 * together with "parsers" which can convert both to and from these formats. A format is defined grammatically, by
 * composing simpler pre-existing formats into more complex assemblies with the help of general-purpose combiners.
 * We prioritize ease-of-use and expressivity over efficiency: parsers defined with this library may be slower than
 * those defined with other libraries, but our goal is for them to be more maintainable in certain dimensions
 * than alternatives. </p>
 *
 * <p> We take the position that serialization is a separate and orthogonal concern from domain modeling. Domain types
 * ought not favor a particular mode of serialization, as business needs can easily change over time. Formats can be
 * authored using this library without any need to modify the domain types they operate on. Moreover, where other
 * libraries may infer details about the serialization format by inspecting the class definitions of domain types, we
 * accept some potential overhead in repeating ourselves to cleanly separate the concern of what <i>developers</i> call
 * a field from what <i>clients</i> call it -- and whether their data is structually organized the same way at all. </p>
 *
 * <p> We <b>eschew reflection and convention</b> in favor of explicit control over how Java values are modeled in JSON.
 * Other libraries may require little to no configuration by relying on reflection and convention, but needs outside
 * the happy path begin to look very different (and ad-hoc) from where you started. This library tries to provide a
 * uniform and consistent authoring experience: if you can author a simple format, odds are you know everything you need
 * to author a much more complex format. </p>
 *
 * <p> At the same time, we provide a flexible foundation for building custom parsing logic. It is entirely possible
 * to define a reflection-based parser as a custom implementation of {@link gov.nasa.jpl.aerie.json.JsonParser}, then
 * use it as a building block just like any of the provided parsers. Custom needs of any kind need only implement
 * that interface. </p>
 *
 * <p> Lastly, we do not parse JSON documents out of strings, but rather work with values of
 * type {@code javax.json.JsonValue}. Any library that produces and consumes these values can be used to bridge the
 * last gap from this library to the filesystem or network. </p>
 *
 * <h2> Defining a format </h2>
 *
 * <p> As a running example, consider the type of expressions below, which models a DSL of operations on integers
 * and strings. (Note in particular that expressions are classified into integer-valued expressions and string-valued
 * expressions.) This DSL may admit multiple serialized representations, such as a traditional infix representation
 * (<code>#(1 + 1) .. "3"</code>) and a tree-formatted JSON representation, so we do not want to privilege one
 * representation by implementing it as a method on the expression type itself.</p>
 *
 * {@snippet :
 * public sealed interface Expr<T> {
 *   record Num(int value) implements Expr<Integer> {}
 *   record Negate(Expr<Integer> operand) implements Expr<Integer> {}
 *   record Add(Expr<Integer> left, Expr<Integer> right) implements Expr<Integer> {}
 *
 *   record Str(String value) implements Expr<String> {}
 *   record ToString(Expr<Integer> operand) implements Expr<String> {}
 *   record Concat(Expr<String> left, Expr<String> right) implements Expr<String> {}
 * } }
 *
 * <p> A JSON format for this type must capture the top-level alternatives amongst the kinds of expression,
 * the mid-level group of fields within each alternative, and the base-level recursion back to the top. In fact,
 * most types can be thought of as a sum (alternatives) of products (fields) of other types; when the "other type"
 * is our original type, we have recursion. Often, there is only one option or only one field, so this hierarchy
 * simplifies for many types. </p>
 *
 * <p> Because almost any compound type can be modeled as a sum of products, this library provides general-purpose
 * combiners for describing formats that follow this structure. You may build custom combiners for special needs,
 * or even build parsers without using combiners at all -- especially when working with types that do not break down
 * into independent pieces in this way -- but the provided combiners should be useful in most cases. </p>
 *
 * <p> Now, let's see how to build up a parser for our {@code Expr} type.</p>
 *
 * {@snippet :
 *   final JsonParser<Expr.Num> numP
 *     = intP
 *     . map(Expr.Num::new, Expr.Num::value);
 *
 *   final JsonParser<Expr.Str> strP
 *     = stringP
 *     . map(Expr.Str::new, Expr.Str::value));}
 *
 * <p> The {@link gov.nasa.jpl.aerie.json.BasicParsers#intP} and {@link gov.nasa.jpl.aerie.json.BasicParsers#stringP}
 * parsers are provided by {@link gov.nasa.jpl.aerie.json.BasicParsers}, and can be statically imported for brevity.
 * They work with the {@code Integer} and {@code String} type, respectively. In order to adapt these to our custom
 * {@code Expr} subclasses, we use the {@link gov.nasa.jpl.aerie.json.JsonParser#map} helper method, which takes two
 * functions: a conversion to the new type from the current type, and a conversion from the new type back to the current
 * type. Here, we are only constructing and deconstructing a wrapper around a single value.  </p>
 *
 * {@snippet :
 *   static JsonObjectParser<Expr.Negate> negateP(final JsonParser<Expr<Integer>> integerExprP) {
 *     return productP
 *         . field("operand", integerExprP)
 *         . map(Expr.Negate::new, Expr.Negate::operand);
 *   }
 *
 *   static JsonObjectParser<Expr.ToString> toStringP(final JsonParser<Expr<Integer>> integerExprP) {
 *     return productP
 *         . field("operand", integerExprP)
 *         . map(Expr.ToString::new, Expr.ToString::operand);
 *   }}
 *
 * <p> Our next two parsers depend on a parser we haven't defined yet -- the top-level integer expression and string
 * expression parsers. Since the top-level parsers, in turn, depend on these individual parsers, we will have a cyclic
 * dependency to handle no matter where we started. Instead of defining these parsers immediately, we <i>defer</i>
 * their construction until later, passing it the top-level parser as an argument once we have it. (We'll see how to close
 * the cycle momentarily.) </p>
 *
 * <p> In addition, notice that we are using the {@link gov.nasa.jpl.aerie.json.BasicParsers#productP} combiner here --
 * which specifies a JSON object whose fields are described by other parsers -- rather than using {@code integerExprP}
 * directly. There are two reasons for this! First, we want parsers to be "productive", which means that they should
 * consume some part of the input before descending into a subparser. This is not always a hard-and-fast rule, but since
 * our grammar is recursive, we <i>must</i> make progress on the input before cycling back to the same point in the
 * grammar. Otherwise, we will have an infinite loop on our hands! </p>
 *
 * <p> The other reason not to use `integerExprP` directly is because the operators described by these parsers are
 * simply two of many, and we need a way to distinguish these options from the others. When we collect these parsers
 * together into one parser of alternatives, we will extend them with an additional "op" field taking on a unique value.
 * Notice that these methods return a {@link gov.nasa.jpl.aerie.json.JsonObjectParser} rather than the more generic
 * {@link gov.nasa.jpl.aerie.json.JsonParser}: the former allows the format to be extended with additional fields. </p>
 *
 * {@snippet :
 *   static JsonParser<Expr<Integer>> integerExprP(final JsonParser<Expr<Integer>> integerExprP) {
 *     @SuppressWarnings("unchecked")
 *     final var intExprClass = (Class<Expr<Integer>>) (Object) Expr.class;
 *
 *     return chooseP(
 *         numP,
 *         sumP("op", intExprClass, List.of(
 *             new Variant<>("+", Expr.Add.class, addP(integerExprP)),
 *             new Variant<>("-", Expr.Negate.class, negateP(integerExprP))
 *         )));
 *   }
 *
 *   final JsonParser<Expr<Integer>> integerExprP
 *       = recursiveP(selfP -> integerExprP(selfP)); }
 *
 * <p> Here, {@code integerExprP} is defined as a method. Just like the previous parsers, its construction depends on
 * a parser that doesn't exist yet -- only, in this case, it depends on itself.
 * The {@link gov.nasa.jpl.aerie.json.BasicParsers#recursiveP(java.util.function.Function)} combiner ties the knot on
 * such a dependency cycle, feeding the given factory function a handle to a mutable location that <i>will</i>,
 * eventually, contain a valid parser -- but only once the factory returns one. For this reason, it is important that
 * the factory not <i>invoke</i> the provided parser, only use it to construct a bigger (productive!) parser. </p>
 *
 * <p> The {@code integerExprP} parser itself is built using two different combiners for handling alternatives.
 * The first, {@link gov.nasa.jpl.aerie.json.BasicParsers#chooseP(gov.nasa.jpl.aerie.json.JsonParser[])}, models
 * an untagged sum: it can't tell immediately which alternative a particular JSON document is a representation of,
 * so it attempts each subparser in turn until it finds one that works. It is very easy to accidentally define
 * overlapping alternatives; be careful to ensure that values covered by one alternative are not covered
 * by another! </p>
 *
 * <p> The {@link gov.nasa.jpl.aerie.json.SumParsers#sumP(java.lang.String, java.lang.Class, java.util.List)} combiner,
 * on the other hand, is a tagged sum: it associates to every alternative an extra field, whose fixed value is distinct
 * for each alternative. This makes it fast and reliable to determine which subparser reigns for a particular document,
 * but makes it less general than {@code chooseP}. The {@code sumP} combiner is also specialized to situations where
 * the domain types are described by a subclass hierarchy; it cannot be used for modeling, say,
 * {@link java.util.Optional}, whose alternatives are not detected with {@code instanceof}. </p>
 *
 * <p> Our expression parser uses {@code sumP} to describe operations using an "op" field, whose value (either "+" or
 * "-") determines the remaining fields. We use {@code chooseP} to allow numbers to be written directly, rather than
 * wrapping them in an extra object like the other cases. The alternatives under {@code chooseP} have no overlap in
 * either the JSON format or the Java type hierarchy, so this is a safe use of {@code chooseP}. </p>
 *
 * <p> The case for string expressions is directly analogous: </p>
 *
 * {@snippet :
 *   static JsonParser<Expr<String>> stringExprP(final JsonParser<Expr<String>> stringExprP) {
 *     @SuppressWarnings("unchecked")
 *     final var stringExprClass = (Class<Expr<String>>) (Object) Expr.class;
 *
 *     return chooseP(
 *         strP,
 *         sumP("op", stringExprClass, List.of(
 *             new Variant<>("++", Expr.Concat.class, concatP(stringExprP)),
 *             new Variant<>("$", Expr.ToString.class, toStringP(integerExprP))
 *         )));
 *   }
 *
 *   final JsonParser<Expr<String>> stringExprP
 *       = recursiveP(selfP -> stringExprP(selfP)); }
 *
 * <p> Now, if {@code stringExprP} models the root of our expression grammar, we can invoke
 * {@link gov.nasa.jpl.aerie.json.JsonParser#parse(javax.json.JsonValue)} on it to convert a JSON document into an
 * {@code Expr<String>}, or invoke {@link gov.nasa.jpl.aerie.json.JsonParser#unparse(java.lang.Object)} to convert
 * an {@code Expr<String>} into a JSON document. As a bonus, the {@link gov.nasa.jpl.aerie.json.JsonParser#getSchema()}
 * method will produce a JSON Schema-compliant document describing the class of JSON documents modeled by
 * this parser! </p>
 */
package gov.nasa.jpl.aerie.json;

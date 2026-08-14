/**
 * Definitions for simulating and solving over real-valued resources.
 *
 * <p>
 *   In general, a dynamics gives an embedding of an interval of time into a space of values.
 *   We expect these embeddings to be <i>continuous</i>: a closed set of values
 *   should be mapped onto by a closed interval of time.
 * </p>
 *
 * <p>
 *   We also want a class of continuous maps from our space of values into the Sierpinski space of boolean valuations.
 *   We call these maps <i>conditions</i>, and each is uniquely given by a choice of closed set in the space of values.
 *   Due to computability and representability concerns, we restrict ourselves further to <i>compact</i> sets.
 * </p>
 *
 * <p>
 *   For real-valued resources, we want such conditions to be thresholds.
 *   The appropriate space is then the one-dimensional Euclidean space,
 *     where compact sets are finite unions of closed intervals.
 *   A dynamics may be any continuous embedding of a time interval;
 *     we select a few useful classes of dynamics for our use.
 * </p>
 */
package gov.nasa.jpl.aerie.merlin.framework.resources.real;

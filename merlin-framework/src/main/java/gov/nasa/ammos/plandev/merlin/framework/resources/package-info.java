/**
 * Definitions for constructing time-varying resources.
 *
 * <p>
 *   A <b>mission resource</b> is an entity whose details may vary over the course of the mission,
 *   and which influences the success of the mission in some way.
 * </p>
 *
 * <p>
 *   Multiple resources may be determined by the same shared state.
 *   Such resources are defined by a <b>model</b> which determines the dynamics of each of the coupled resources.
 *   Thus, a <i>resource</i> is defined by a <i>model</i>.
 *   (We include singlet resources -- resources whose state is not shared by others -- as a limiting case.)
 * </p>
 *
 * <p>
 *   Pragmatically, a dynamics at one time may not be a reasonable approximation at a later time.
 *   As a resource is derived from zero or more backing states, we may take the minimum over calls
 *   to {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType#getExpiry(java.lang.Object)} as the amount of time
 *   for which a resource's current dynamics is legal.
 * </p>
 *
 * <p>
 *   We may analyze a resource's dynamics in one of two ways:
 * </p>
 * <ul>
 *   <li>
 *     Determining the value of a resource at a given time, under its current dynamics; or
 *   <li>
 *     Determining the next time at which some condition is held true.
 * </ul>
 */
package gov.nasa.jpl.aerie.merlin.framework.resources;

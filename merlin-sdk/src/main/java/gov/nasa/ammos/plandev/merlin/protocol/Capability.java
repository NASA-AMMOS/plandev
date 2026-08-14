package gov.nasa.jpl.aerie.merlin.protocol;

/**
 * A Capability has object identity. Instances of a Capability type can be distinguished from other instances
 * even if they would otherwise be observationally indistinguishable.
 *
 * A capability is distinguished from a mere trait in that a capability may itself be passed to the service provider
 * via methods on another trait. The service provider needs to be able to verify that the capability it receives
 * is indeed one it produced, and not a forgery or produced by a different provider of the same service.
 */
public @interface Capability {}

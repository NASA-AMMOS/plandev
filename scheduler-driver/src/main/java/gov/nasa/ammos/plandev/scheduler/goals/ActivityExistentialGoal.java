package gov.nasa.jpl.aerie.scheduler.goals;

/**
 * describes the desired existence of an activity within the solution plan
 */
public class ActivityExistentialGoal extends Goal {

  /**
   * the builder can construct goals piecemeal via a series of method calls
   */
  public abstract static class Builder<T extends Builder<T>> extends Goal.Builder<T> {

    /**
     * sets the activity instance sharing preference for the goal
     *
     * this specifier is optional (the default is Jointly). it replaces any
     * previous specification.
     *
     * @param custody IN the custody strategy for the goal to adopt
     * @return this builder, ready for additional specification
     */
    public T owned(ChildCustody custody) {
      childCustody = custody;
      return getThis();
    }

    protected ChildCustody childCustody = ChildCustody.Jointly;

    /**
     * {@inheritDoc}
     */
    @Override
    public ActivityExistentialGoal build() { return fill(new ActivityExistentialGoal()); }

    /**
     * populates the provided goal with specifiers from this builder and above
     *
     * typically called by any derived builder classes to fill in the
     * specifiers managed at this builder level and above
     *
     * @param goal IN/OUT a goal object to be filled with specifiers from this
     *     level of builder and above
     * @return the provided goal object, with details filled in
     */
    protected ActivityExistentialGoal fill(ActivityExistentialGoal goal) {
      //first fill in any general specifiers from parent
      super.fill(goal);

      if (childCustody == null) {
        throw new IllegalArgumentException(
            "activity existential goal requires non-null childCustody policy");
      }
      goal.childCustody = childCustody;

      return goal;
    }

  }//Builder

  /**
   * ctor creates new empty goal without identification / specification
   *
   * client code should use derived type builders to instance goals
   */
  public ActivityExistentialGoal() { }

  public ChildCustody getChildCustody(){
    return this.childCustody;
  }

  /**
   * the activity instance sharing strategy used by this goal when
   * calculating its own satisfaction
   */
  protected ChildCustody childCustody;

}

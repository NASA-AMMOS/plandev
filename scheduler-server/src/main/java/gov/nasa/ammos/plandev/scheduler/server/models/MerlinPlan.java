package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MerlinPlan {

  private final Map<ActivityDirectiveId, ActivityDirective> activities;

  public MerlinPlan(){
    activities = new HashMap<>();
  }

  public void addActivity(final ActivityDirectiveId id, final ActivityDirective activity){
    if(activities.containsKey(id)){
      throw new IllegalArgumentException("Activity with same id is already in the plan");
    }
    activities.put(id, activity);
  }

  public Map<ActivityDirectiveId, ActivityDirective> getActivitiesById(){
    return Collections.unmodifiableMap(activities);
  }

  public Optional<ActivityDirective> getActivityById(final ActivityDirectiveId id){
    return Optional.ofNullable(activities.get(id));
  }

}

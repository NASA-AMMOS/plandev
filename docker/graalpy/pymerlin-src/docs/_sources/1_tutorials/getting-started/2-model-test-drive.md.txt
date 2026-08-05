# Model Test Drive

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

:::{warning}
This page is a stub, since python mission models cannot yet be uploaded to Aerie.
:::

Within your IDE, compile the model (`./gradlew assemble` should do the trick) and make sure it built successfully by checking `build/lib` for a new `missionmodel.jar` file.

Follow [these instructions](https://ammos.nasa.gov/aerie-docs/planning/upload-mission-model/) to upload your `.jar` file, and give your model a name and version number (e.g. SSR Model version 1.0). Next, you can follow [these instructions](https://ammos.nasa.gov/aerie-docs/planning/create-plan-and-simulate/#instructions) to create a new plan. Pick the model you just compiled to build your plan off of and name your plan `Mission Plan 1` and give it a duration of `1 day`. Click "Create" and click on the newly created plan to open it, which should take you to a view with the plan timeline in the center view panel.

On the left panel, you should see your `collect_data` activity type, which you can drag and drop onto the "Activities" row in the timeline. Your `recording_rate` resource should also appear as a row in the timeline, but with no values applied yet since we haven't simulated. Put two activities in your plan and click on the second one (the first one we will leave alone and let it use default values). On the right panel, you should now see detailed information about your activity. Look for the "Parameters" section and you will see your rate and duration parameters, which you can modify. Try modifying the rate above `100.0` and you will see a warning icon appear, which you can hover over and see the message we wrote into a Validation earlier. Modify the rate back to `20` and change the default duration to `2 hours`.

:::info

When activity types are initially added to the plan, they are shown with a play button icon and don't have a duration. We call these "activity directives", and it is these activities that you are allowed to modify by changing parameters, timing, etc. Once a simulation has been performed, one or more activities will appear below the directive, which are the activity instances. These actually have a duration (based on their effect model) and are the result of the simulation run.

:::

On the top menu bar, click "Simulation" and then "Simulate". After you see a green checkmark, `recording_rate` should be populated with a value profile. The value should begin at 0.0 (since we initialized it that way in the model) and pop up to `10` for the first activity and `20` for the second. You'll also see that the activity instances below the activity directives (see note above) have durations that match the arguments we provided. At this point, your view will look similar to the screenshot below.

![Tutorial Plan 1](assets/Tutorial_Plan_1.png)

At this point, we could go into more detail about how you can [edit the timeline](https://nasa-ammos.github.io/aerie-docs/planning/timeline-editing/), edit your UI view, or view simulation history, but instead we will move back to our IDE and add some more complexity to our model.
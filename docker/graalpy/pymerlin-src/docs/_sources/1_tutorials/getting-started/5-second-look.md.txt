# Second Look

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

With our second activity and corresponding resources built, let's compile the model again and upload it into Aerie (if
you forget how to do this, refer to the [Model Test Drive Page](2-model-test-drive) for simple instructions and
references). Build a new plan off of the model you just uploaded, name your plan `Mission Plan 2`, and give it a
duration of `1 day`. When you open this plan, you will see your two activity types appear in the left panel, which you
can drag and drop onto the plan. Add two `change_mag_mode` activities and change the parameter of the first one
to `HIGH_RATE`. Add a `collect_data` activity in between the two `change_mag_mode` activities and then simulate.

You should now see our three resources populate with values in the timeline below. You'll notice that now
the `recording_rate` resource starts at zero until the `mag_data_mode` changes to `HIGH_RATE`, which pops up the rate
to `5 Mbps`. Then, the `collect_data` activity increases the rate by another `10` to `15 Mbps`, but immediately decreases
after the end of the activity. Finally, the `mag_data_mode` changes to `LOW_RATE`, which takes the rate down to `0.5 Mbps`
until the end of the plan.

At this point, you can take the opportunity to play around with
Aerie's [Timeline Editing](https://ammos.nasa.gov/aerie-docs/planning/timeline-editing/) capability to change the colors
of activities or lines or put multiple resources onto one row. Try putting the `mag_data_mode` and `mag_data_rate` on the
same row so you can easily see how the mode changes align with the rate changes and change the color of `mag_data_rate` to
red. With these changes you should get something similar to the screenshot below

![Tutorial Plan 2](assets/Tutorial_Plan_2.png)

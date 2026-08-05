# Integral Method Comparison

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

Now that we have explored multiple methods to implement integration in Aerie, let's compare all of the methods in the
Aerie UI. To make things more interesting, use the 2nd approach to the `Polynomial` method so we can see how that
approach enforces a data volume capacity. Compile the current version of the model (`./gradlew assemble`) and upload it
into Aerie. Build a new `1 day` plan off of that model and call it "Mission Plan 3".

For this plan, throw a couple of `collect_data` activities near the beginning of the plan, create a `change_mag_mode`
activity after those activities in the first half of the plan and set that activity's parameter to `HIGH_RATE`. Throw
one more `collect_data` and `change_mag_mode` activity near the end of the plan to make sure we get a plan that goes over
our data capacity threshold. With our simple plan built, go ahead and simulate the plan to see the resulting resource
profiles.

The easiest way to compare our four integration methods is to use
Aerie's [Timeline Editing](https://ammos.nasa.gov/aerie-docs/planning/timeline-editing/) capability to build a row that
includes all four of our data volume resources:

- `ssr_volume_simple`
- `ssr_volume_sampled`
- `ssr_volume_upon_rate_change`
- `ssr_volume_polynomial`

If you do that, you'll get a timeline view that looks something like the screenshot below

![Tutorial Plan 3](assets/Tutorial_Plan_3.png)

Looking at `ssr_volume_simple`, you'll see that data volume increases at the end of each `collect_data` activity, and for
the first two activities, the result at the end of the activity is consistent with the other volumes. You may recall
that we did not implement a data volume integration for the `change_mag_mode` activity for `ssr_volume_simple` (although
we could have with some work), so as soon as one of those activities is introduced into our plan, our volume is no
longer valid.

`ssr_volume_sampled` has a nice looking profile when zoomed out at the expense of computing many points, which you can
see if you zoom into a shorter time span. If you zoom far enough, you can see the stair-step associated with computation
of each sampled point. If we were to change our sampling interval to something larger, we would lose some accuracy in
our volume calculation if the activity start/end times aren't aligned with are sample points.

`ssr_volume_upon_rate_change` has much fewer points, but you can see that it produces the same volume as
our `ssr_volume_polynomial` resource at the time points it computes until we go above our maximum
capacity. `ssr_volume_polynomial` has same computed points as `ssr_volume_upon_rate_change`, but has linear profile
segments in between points. It also has an additional point once it reaches the capacity threshold, and then it remains
at that threshold for the remainder of the plan (we don't have any downlinks or we would see the volume decrease).

Hopefully looking at the various methods of integrating in Aerie has given you some insight into the modeling constructs
available to you. You can do a ton with what you have learned thus far, but next we'll go over some additional
capabilities you will likely find useful as you build models with Aerie.

# Sim Configuration

:::{warning}
This page is under construction. Please bear with us as we port
our [Java tutorial](https://nasa-ammos.github.io/aerie-docs/tutorials/mission-modeling/introduction/) to python.
:::

There is often a need for certain aspects of a model to be exposed to the planner to provide flexibility to tweak and
configure the model prior to a simulation run. The Aerie modeling framework provides
a [simulation configuration](https://ammos.nasa.gov/aerie-docs/mission-modeling/configuration/) interface to satisfy
this need. In our SSR model, we will expose a couple variables that already exist in our code: the sample interval for
our `SSR_Volume_Sampled` resource and the SSR max capacity defined as part of the `ssr_volume_polynomial` resource
definition. We will also create a new model configuration for setting the initial state of the `mag_data_mode`.

Back when we initially grabbed the mission model template to give us a jumping off point for our model, you may recall
that the template provided a `Configuration` class, and that class is already passed into the top-level `Mission` class
as a parameter. Taking a look at the `Configuration` class (which is actually
a [java record](https://www.baeldung.com/java-record-vs-final-class)), you'll see there is already a static method there
called `defaultConfiguration()` that uses
the [`@Template` annotation](https://ammos.nasa.gov/aerie-docs/mission-modeling/parameters/#export-template). This type
of annotation assumes every variable within the parent class should be exposed as simulation configuration (or a
parameter if you use this within activities) with a default value. So, in our case, we will declare three member
variables and give them all default values that match the values we have for them in the `DataModel` class, which we
will soon replace with references to this configuration.

```java
public static final Double SSR_MAX_CAPACITY = 250.0;

public static final long INTEGRATION_SAMPLE_INTERVAL = 60;

public static final MagDataCollectionMode STARTING_MAG_MODE = MagDataCollectionMode.OFF;
```

In order to hook up these member variables to our record, we need to add three constructor parameters and then update
the `defaultConfiguration()` method to pass in these default values to construct a record with default values. Once we
do this, we get a `Configuration` record that looks like this:

```java
package missionmodel;

import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;

public record Configuration(Double ssrMaxCapacity,
                            long integrationSampleInterval,
                            MagDataCollectionMode startingMagMode) {

  public static final Double SSR_MAX_CAPACITY = 250.0;

  public static final long INTEGRATION_SAMPLE_INTERVAL = 60;

  public static final MagDataCollectionMode STARTING_MAG_MODE = MagDataCollectionMode.OFF;

  public static @Template Configuration defaultConfiguration() {
    return new Configuration(SSR_MAX_CAPACITY,
                             INTEGRATION_SAMPLE_INTERVAL,
                             STARTING_MAG_MODE);
  }
}
```

Now, when Aerie loads in our model, the member variables above will be exposed as simulation configuration with defaults
set to the defaults defined in this record. However, at the moment, changing the values from their defaults won't
actually change the behavior of the simulation because our `DataModel` doesn't yet know about this configuration. Within
our top-level `Mission` class, we need to pass our configuration into `DataModel` via its constructor

```java
this.dataModel = new DataModel(this.errorRegistrar, config);
```

and then update the `DataModel` class constructor to include `Configuration` as an argument:

```java
public DataModel(Registrar registrar, Configuration config) { ... }
```

Now we must find references to our original, hard-coded values for our configuration and replace them with references to
our `config` object.

Here is what this looks like for `ssrMaxCapacity`

```java
var clampedIntegrate = PolynomialResources.clampedIntegrate( scale(
    asPolynomial(this.recording_rate), 1e-3),
    PolynomialResources.constant(0.0),
    PolynomialResources.constant(config.ssrMaxCapacity()),
    0.0);
```

and `integrationSampleInterval`

```java
INTEGRATION_SAMPLE_INTERVAL = Duration.duration(config.integrationSampleInterval(), Duration.SECONDS);
```

Note that for the sample interval, we had to move from a hardcoded definition as part of the variable declaration and
move the definition to the constructor, which you could put on the line following the registration of
the `SSR_Volume_Sampled` resource.

Our final configuration parameter, `startingMagMode`, is not quite as straightforward as the other two because in
addition to ensuring that the initial value of `mag_data_mode` is set correctly, we need to make sure that the
initial `recording_rate` also takes into account the `mag_data_rate` associated with the initial `mag_data_mode`. We can
achieve this by switching around the order of construction so that the `recording_rate` is defined after the mag mode and
rate. We also need to make sure the `previousrecording_rate` used to compute our `ssr_volume_upon_rate_change` resource is
set to the initial value of `recording_rate`. The resulting code will look like this

```java
self.mag_data_mode = registrar.cell(discrete(config.startingMagMode()));
registrar.discrete("mag_data_mode",self.mag_data_mode, new EnumValueMapper<>(MagDataCollectionMode.class));

self.mag_data_rate = map(mag_data_mode, MagDataCollectionMode::get_data_rate);
registrar.discrete("mag_data_rate", self.mag_data_rate, new DoubleValueMapper());

recording_rate = registrar.cell(discrete(currentValue(mag_data_rate)/1e3));
registrar.discrete("recording_rate", recording_rate, new DoubleValueMapper());
previousrecording_rate = currentValue(recording_rate);
```

Now you should be ready to try this out in the Aerie UI. Go ahead and compile your model with simulation configuration
and upload it to Aerie. Build whatever plan you'd like and then before you simulate, in the left panel view, select "
Simulation" in the dropdown menu. You should now see your three configuration variables appear under "Arguments"

![Simulation Config](assets/Simulation_Config.png)

Aerie is smart enough to look at the types of the configuration variables and generate a input field in the UI that best
matches that type. So, for example, the `startingMagMode` is a simple drop down menu with the only options available
being members of the `MagDataCollectionMode` enumeration.

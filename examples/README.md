# Examples

This directory contains several example mission models.

| model                   | purpose                                                                                                            |
|-------------------------|--------------------------------------------------------------------------------------------------------------------|
| banananation            | banananation is the most commonly used model for unit testing. It should exercise most mission modeling features   |
| config-with-defaults    | Used to test the @WithDefaults feature of mission model configurations                                             |
| config-without-defaults | Used to test the behavior when @WithDefaults is missing from a mission model configuration                         |
| foo-missionmodel        | Used for slightly more complex testing than Banananation. Emphasis is on edge cases and longer run times           |
| minimal-mission-model   | The absolute minimum to technically be a mission model. Helps test the base case of "zero resources, one activity" |
| model-migration-1       | Used to test migrating a plan from one mission model to another. This is used as the original mission model        |
| model-migration-2       | Used to test migrating a plan from one mission model to another. This is used as the modified mission model        |
| streamline-demo         | This model demonstrates features of the `gov.nasa.jpl.aerie.contrib.streamline` library                            |

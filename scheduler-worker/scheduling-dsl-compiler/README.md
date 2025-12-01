# Scheduling eDSL API 

This is the documentation of the API of the PlanDev scheduling eDSL. 

You can find another documentation on automated scheduling in PlanDev [here](https://github.com/NASA-AMMOS/plandev/wiki/Scheduling-Guide).

## Known general issues

Activities with uncontrollable durations have been found to behave somewhat unpredictably, in terms of when they are placed. This has to do with how temporal constraints interact with the unpredictability of the durations. Finding when an activity will start while subject to temporal constraint involves search.

## For developers 
Files in the ./constraints directory are copied from the constraints-dsl-compiler using a gradle action.
These files should not be edited, or committed to version control.

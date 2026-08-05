# Build a PlanDev-compatible JAR file

`pymerlin package` turns a Python model into a mission-model JAR you can upload to a deployed
PlanDev instance. The model then runs **in-process** inside the PlanDev worker via an embedded
GraalPy interpreter — no subprocess, no separate Python install on the worker. See
[Architecture](../architecture.md) for how that works.

## Package the model

```shell
pymerlin package --model path/to/model.py:MissionClassName --out mission-model.jar
```

This copies the prebuilt shim JAR (`pymerlin-shim.jar`, shipped inside the `pymerlin` pip
package), bundles your model source into it under `pymerlin_models/`, and stamps the model
reference into the JAR manifest as `Pymerlin-Model-Ref`. If your model file sits next to an
`__init__.py`, the whole package directory is bundled so intra-package imports keep working.

You don't need Java installed to run `pymerlin package` — it ships a prebuilt shim.

## Upload it

Follow the PlanDev docs to
[upload the mission model](https://nasa-ammos.github.io/aerie-docs/planning/upload-mission-model/),
give it a name and version, then
[create a plan and simulate](https://nasa-ammos.github.io/aerie-docs/planning/create-plan-and-simulate/).

## What the worker must provide

The JAR carries only the shim, its `gson` dependency, and your model source — **not** the
Python runtime or any Python packages. Those come from the PlanDev worker image, which ships an
embedded GraalPy plus a pre-built virtual environment containing `pymerlin`, `numpy`, and
`spiceypy`. If your model imports a package that isn't in that venv, it's an **image rebuild**,
not a JAR change. The full worker-image contract — the venv layout, the exact package set, and
how to add a package — is documented in the project
[README](https://github.com/mattdailis/pymerlin) ("Worker-image contract") and `roadmap.md`
§4/§11.2.

## Testing without uploading

You don't need a JAR (or a deployed PlanDev) to exercise a model during development: pymerlin's
pure-Python [`simulate()`](../1_tutorials/getting-started/2-model-test-drive.md) runs the same
`@MissionModel` class locally. Package and upload when you want the model in a real PlanDev plan;
use `simulate()` for fast iteration.

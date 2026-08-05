# Quickstart

This section describes how to get started with `pymerlin` 🎉

## Installation

First, make sure you have python >=3.10 installed on your machine.

- Python: [https://www.python.org/downloads/release/python-3120/](https://www.python.org/downloads/release/python-3120/)

You do **not** need Java to write models or run local simulations: pymerlin's `simulate()`
is a pure-Python engine. Java (and GraalPy) only enter the picture when a packaged model runs
inside a deployed PlanDev worker — see [Architecture](architecture.md) — and that runtime is
supplied by the worker image, not your laptop.

Make a [python virtual environment](https://docs.python.org/3/library/venv.html) for your project.

After activating that environment, install `pymerlin` with the following terminal command:

```shell
pip install pymerlin
```

Check that the installation succeeded by running:

```shell
python3 -c "import pymerlin; pymerlin.checkout()"
```

If you see `pymerlin checkout successful: All systems GO 🚀`, you're ready to get started with the [tutorial](1_tutorials/getting-started/index.md).
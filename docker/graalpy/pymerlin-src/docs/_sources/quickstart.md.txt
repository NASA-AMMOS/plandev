# Quickstart

This section describes how to get started with `pymerlin` 🎉

## Installation

First, make sure you have Java 21 JRE and python >=3.11 installed on your machine.

- Java: [https://adoptium.net/temurin/releases/](https://adoptium.net/temurin/releases/)
- Python: [https://www.python.org/downloads/release/python-3120/](https://www.python.org/downloads/release/python-3120/)

Once those are ready, make a [python virtual environment](https://docs.python.org/3/library/venv.html) for your project.

After activating that environment, install `pymerlin` with the following terminal command:

```shell
pip install pymerlin
```

Check that the installation succeeded by running:

```shell
python3 -c "import pymerlin; pymerlin.checkout()"
```

If you see `pymerlin checkout successful: All systems GO 🚀`, you're ready to get started with the [tutorial](1_tutorials/getting-started/index.md).
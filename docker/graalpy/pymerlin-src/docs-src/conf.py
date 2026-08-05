# Configuration file for the Sphinx documentation builder.
#
# For the full list of built-in configuration values, see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Project information -----------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#project-information

project = 'pymerlin'
copyright = '2024, California Institute of Technology'
author = 'Matthew Dailis'

# Keep project version synchronized with pyproject.toml
import os, tomllib

with open(os.path.join(os.path.dirname(__file__), "../pyproject.toml"), "rb") as f:
    release = tomllib.load(f)["project"]["version"]

# -- General configuration ---------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#general-configuration

import os
import sys

sys.path.insert(0, os.path.abspath('..'))  # Source code dir relative to this file

extensions = [
    # "autodoc2",
    'myst_parser',
    'sphinx.ext.doctest',
    'sphinx.ext.autodoc',  # Core library for html generation from docstrings
    'sphinx.ext.autosummary',  # Create neat summary tables
    'sphinx.ext.coverage',
    'sphinx.ext.napoleon',
]

autodoc2_packages = [
    "../pymerlin",
]

autodoc2_docstring_parser_regexes = [
    # this will render all docstrings as Markdown
    (r".*", "myst"),
]

myst_enable_extensions = ["colon_fence"]

autodoc2_index_template = """
API Reference
=============

Here, you can find the details of functions, classes, and data structures provided by the `pymerlin` package.

.. toctree::
   :titlesonly:
{% for package in top_level %}
   {{ package }}
{%- endfor %}
"""

templates_path = ['_templates']
exclude_patterns = ['_build', 'Thumbs.db', '.DS_Store']

# -- Options for HTML output -------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#options-for-html-output

html_theme = 'furo'
html_static_path = ['_static']

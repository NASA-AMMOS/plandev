# Documentation

Contributions to this documentation are welcome!

This documentation lives in the `docs-src` directory, and is built
by running the following:

```shell
cd docs-src
make clean html
```

This overwrites the `docs` directory, so opening `docs/index.html` will let you view the generated documentation site.

Commit both `docs-src` and `docs` to git.

This documentation is built using [sphinx](https://www.sphinx-doc.org/en/master/) with the [MyST](https://mystmd.org/)
plugin, using the [Furo](https://pradyunsg.me/furo/) theme. Python docstrings are automatically parsed and included in
this documentation using the sphinx-autodoc extension.

## Target Audience

When writing documentation, it's important to have a specific target audience in mind, and to keep that consistent across
the documentation. Tutorials should assume very little knowledge - how-to guides can assume a little more, and background
pages can go into great detail.

### Assumed programming knowledge
As a python library, user-facing documentation should be targeted towards people who may know a little python, but maybe
not a lot of it. There's no need to explain basic concepts like functions or variables, but keywords like `async` and 
`await` deserve a brief comment and a link to python documentation.

Little to know Java knowledge should be expected.

The Background section can get a bit more technical in some aspects, but should still include links to relevant
documentation.

The developer-facing pages can assume familiarity with python and Java, but should still link out to specific pages
for libraries or esoteric features.

### Assumed Aerospace knowledge
Provide links to the [Basics of Spaceflight](https://science.nasa.gov/learn/basics-of-space-flight/) where appropriate.
Users of this library are likely to be persuing aerospace degrees, have already attained said degrees, or work for
aerospace companies, so there's no need to spell things out in great detail. What they may be less familiar with is
best practices for representing those concepts in a simulation - that's where this documentation should focus.

## Style

Refer to [https://developers.google.com/style](https://developers.google.com/style) for guidance. Any particularly salient points should be reproduced in this document.

pymerlin is always lowercase. It may be styled as regular text or in a code block like this: `pymerlin` depending on the
context.

## Referencing Aerie

pymerlin documentation should be self-contained for users who are not using an Aerie deployment - this means that
some concepts will be repeated across pymerlin and Aerie documentation. In these scenarios, the relevant Aerie
documentation page should be linked in a "Further reading" section at the bottom of the page.

## Glossary

Keep the [glossary](./glossary) up to date by following instructions here: https://mystmd.org/guide/glossaries-and-terms

## Code snippets
There are two ways to include code snippets:

As python snippets:
````
```python
"YOUR CODE HERE"
```
````

and using doctest-compatible blocks:
```
:::{testcode}
"YOUR CODE HERE"
:::
```

The difference is that the doctest style can be tested by running `make doctest`, which can help detect breaking
changes that require changes to the documentation.

You can also use `testcleanup` to run "hidden" snippets of code, and `testoutput` to assert that the standard output
contains the expected string.
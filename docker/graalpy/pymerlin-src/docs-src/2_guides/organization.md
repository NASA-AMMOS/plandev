# How to organize your project

The intro tutorial has you build your whole mission model and activities in one file. As you write more and more lines
of code for your model, this may become intractable. This guide is intended to describe some considerations when
choosing how to break up your project.

## File-by-subsystem
The most natural way to divide a mission model is by subsystem. One way to achieve this is to..

:::{warning}
But how do we avoid a circular import here?
:::
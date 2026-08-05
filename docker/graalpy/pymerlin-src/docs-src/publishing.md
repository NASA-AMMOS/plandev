# Publishing to pypi

Make sure your `.pypirc` file exists and includes your pypi API key.

Run these commands from the root of the repository

```shell
rm -rf dist
python3 -m build     
python3 -m twine upload dist/*
```
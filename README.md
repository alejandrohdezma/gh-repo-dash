# [gh CLI](https://cli.github.com) extension to get a dashboard of your repositories

It shows the repositories for a certain organization with their latest tag and the number of commits in the default branch since that tag.

![](demo.svg)

## Installation

First you need to ensure you have the [gh CLI](https://cli.github.com) installed and authenticated with your user.

You will also need [`gum`](https://github.com/charmbracelet/gum/blob/main/README.md#installation) installed in your machine.

Then you can proceed with installing the extension:

```bash
gh extension install alejandrohdezma/gh-repo-dash
```

## Usage

```bash
gh repo-dash my-org
```

You also have available the following options:

```
   -q, --query QUERY       The query to use when searching repositories. It is 
                           always prepended with the organization. Check
                           https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories
                           for more information.
   -c, --cache duration    Cache the responses from GitHub, e.g. "3600s", "60m", "1h". Defaults to 1h
   -h, --help              Show this help message
```
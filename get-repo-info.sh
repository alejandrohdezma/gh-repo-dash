#!/bin/bash

REPO=$1
CACHE=$2

repo_info=$(gh api --cache "$CACHE" "repos/$REPO")
default_branch=$(echo $repo_info | jq -r '.default_branch')
description=$(echo $repo_info | jq -r '.description')

tag=$(gh api --cache "$CACHE" "repos/$REPO/releases/latest" 2>/dev/null | jq -r '.tag_name // "Not found"')

if [ "$tag" == "Not found" ]; then
    commits=0
else
    commits=$(gh api --paginate --cache "$CACHE" "repos/$REPO/compare/$tag...$default_branch" -q '.commits | length' | jq -s 'add')
fi

cat <<EOF
{
    "name": "$REPO",
    "latest_tag": "$tag",
    "description": "$description",
    "default_branch": "$default_branch",
    "commits_since_latest_tag": $commits
}
EOF

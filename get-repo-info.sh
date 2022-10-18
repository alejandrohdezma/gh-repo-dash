#!/bin/bash

REPO=$1
CACHE=$2

repo_info=$(gh api --cache "$CACHE" "repos/$REPO")
default_branch=$(echo $repo_info | jq -r '.default_branch')
description=$(echo $repo_info | jq -r '.description')

latest_release=$(
    gh api --cache "$CACHE" "repos/$REPO/releases/latest" 2>/dev/null |
        jq '{tag_name: (.tag_name // "Not found"), published_at: (.published_at // null)}'
)

tag=$(echo $latest_release | jq -r '.tag_name')
published_at=$(echo $latest_release | jq -r '.published_at')

if [ "$published_at" == "null" ]; then
    date="---"
else
    end=$(date -j -f "%Y-%m-%dT%H:%M:%SZ" "$published_at" +%s)
    now=$(date +%s)
    diff=$(((now - end) / 86400))

    if ((diff == 0)); then
        date="Today"
    elif ((diff == 1)); then
        date="Yesterday"
    elif ((diff > 365)); then
        years=$((diff / 365))

        if ((years > 1)); then
            date="$years years ago"
        else
            date="1 year ago"
        fi
    elif ((diff > 30)); then
        months=$((diff / 30))

        if ((years > 1)); then
            date="$months months ago"
        else
            date="1 month ago"
        fi
    else
        date="$diff days ago"
    fi
fi

if [ "$tag" == "Not found" ]; then
    commits=0
else
    commits=$(gh api --paginate --cache "$CACHE" "repos/$REPO/compare/$tag...$default_branch" -q '.commits | length' | jq -s 'add')
fi

cat <<EOF
{
    "name": "$REPO",
    "latest_tag": "$tag",
    "published_at": "$date",
    "description": "$description",
    "default_branch": "$default_branch",
    "commits_since_latest_tag": $commits
}
EOF

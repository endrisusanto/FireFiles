#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
REMOTE_URL="${REMOTE_URL:-https://github.com/endrisusanto/FireFiles.git}"

if [[ -z "$VERSION" ]]; then
  CURRENT="$(sed -n -E 's/^version = "([0-9]+)\.([0-9]+)\.([0-9]+)"/\1 \2 \3/p' windows-worker/Cargo.toml | head -n1)"
  [[ -n "$CURRENT" ]] || { echo "cannot read current version"; exit 1; }
  read -r MAJOR MINOR PATCH <<<"$CURRENT"
  VERSION="$MAJOR.$MINOR.$((PATCH + 1))"
elif [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: ./script.sh [0.1.0]"
  exit 2
fi

TAG="v$VERSION"
IFS=. read -r MAJOR MINOR PATCH <<<"$VERSION"
VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [[ -d .git ]]; then
  rmdir .git 2>/dev/null || {
    echo ".git exists but is not a valid repo; fix it manually"
    exit 1
  }
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git init
fi

git config user.name >/dev/null 2>&1 || git config user.name "FireFiles Bot"
git config user.email >/dev/null 2>&1 || git config user.email "actions@users.noreply.github.com"
git remote get-url origin >/dev/null 2>&1 && git remote set-url origin "$REMOTE_URL" || git remote add origin "$REMOTE_URL"

sed -i -E "s/^version = \"[^\"]+\"/version = \"$VERSION\"/" windows-worker/Cargo.toml
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/" android-app/app/build.gradle.kts
sed -i -E "s/versionCode = [0-9]+/versionCode = $VERSION_CODE/" android-app/app/build.gradle.kts

git add .
git commit -m "release $TAG"
git tag -a "$TAG" -m "release $TAG" 2>/dev/null || git tag -fa "$TAG" -m "release $TAG"
git branch -M main
git push -u origin main
git push origin "$TAG" --force

echo "Release workflow started for $TAG"

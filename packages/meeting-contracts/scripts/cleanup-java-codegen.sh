#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Cleanup Java OpenAPI generator output — keep only model + API source files.
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

DIR="$1"

if [ ! -d "$DIR" ]; then
  echo "Usage: $0 <generated-output-dir>"
  exit 1
fi

# Remove boilerplate that openapi-generator emits for -g java / -g spring
rm -rf \
  "$DIR/src/test" \
  "$DIR/docs" \
  "$DIR/README.md" \
  "$DIR/pom.xml" \
  "$DIR/.gitignore" \
  "$DIR/.openapi-generator-ignore" \
  "$DIR/.github" \
  "$DIR/.travis.yml" \
  "$DIR/.openapi-generator" \
  "$DIR/build.gradle" \
  "$DIR/build.sbt" \
  "$DIR/gradle.properties" \
  "$DIR/gradlew" \
  "$DIR/gradlew.bat" \
  "$DIR/gradle" \
  "$DIR/git_push.sh" \
  "$DIR/api" \
  "$DIR/settings.gradle" \
  "$DIR/src/main/java/org" \
  "$DIR/src/main/resources" \
  "$DIR/src/main/AndroidManifest.xml"

# Keep only API interfaces and model classes under the target package;
# delete shared client base classes (ApiClient, auth, JSON, etc.) because
# they are not compiled by this module and change with generator version.
for f in "$DIR/src/main/java/com/meeting/api/client"/*; do
  if [ -d "$f" ]; then
    # Keep model/ and *Api.java; delete everything else at this level
    base=$(basename "$f")
    case "$base" in
      publicapi|workerinternal)
        for sub in "$f"/*; do
          if [ -d "$sub" ]; then
            subbase=$(basename "$sub")
            if [ "$subbase" != "model" ]; then
              rm -rf "$sub"
            fi
          elif [ -f "$sub" ]; then
            # Keep only *Api.java files
            if [[ $(basename "$sub") != *Api.java ]]; then
              rm -f "$sub"
            fi
          fi
        done
        ;;
      *)
        rm -rf "$f"
        ;;
    esac
  elif [ -f "$f" ]; then
    rm -f "$f"
  fi
done

echo "Cleaned $DIR"

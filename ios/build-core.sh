#!/bin/bash
# Builds the shared Kotlin module (../shared) into HomebaseCore.xcframework for the iOS app. Needs a JDK 17+.
set -e
cd "$(dirname "$0")/.."
./gradlew --no-daemon :shared:assembleHomebaseCoreReleaseXCFramework
echo "HomebaseCore.xcframework is in shared/build/XCFrameworks/release"

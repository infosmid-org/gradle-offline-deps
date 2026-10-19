# Releasing packages

In order to ensure a release is built and published to Maven Central the repo need to be tagged with release version and the tag pushed.

## Performing release

```shell
./gradlew clean javadoc build
```
Commit and wait for snapshot build to be successful, then execute:

```shell
~/git-release.sh
```

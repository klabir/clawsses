# Versioning

The phone and glasses applications are released as one compatible app pair. Their shared version is defined in the root `gradle.properties` file:

```properties
clawsses.versionCode=127
clawsses.versionName=1.3.118
```

Before every distributable release:

1. Increase `clawsses.versionCode`. It must always be a larger integer than every previously installed build.
2. Update `clawsses.versionName` using semantic versioning.
3. Build both applications together with `./gradlew assembleDebug` or the appropriate release task.
4. Verify that both APK manifests contain the new version before installation.

Local rebuilds that are not distributed do not require a version increase. Both Gradle modules consume the same properties, so a release cannot accidentally package different phone and glasses versions.

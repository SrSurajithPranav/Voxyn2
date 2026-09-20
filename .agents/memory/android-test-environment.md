---
name: Android test environment
description: Workspace constraint affecting Android Gradle validation
---

Android Gradle test tasks cannot configure when the workspace has no Android SDK and no local.properties sdk.dir. Code-level review is still possible, but full unit/build validation must run in an SDK-enabled environment.

**Why:** The project uses the Android Gradle plugin, which requires an SDK before even the JVM unit-test task can resolve.

**How to apply:** Check ANDROID_HOME, ANDROID_SDK_ROOT, and local.properties before spending time retrying Gradle validation.
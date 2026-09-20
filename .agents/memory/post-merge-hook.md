---
name: Post-merge setup hook
description: Replit task merges require an explicit post-merge script configured in .replit.
---

The task-merge system reads the post-merge script path from the `.replit` `[postMerge]` section; a script existing in the repository is not enough by itself.

**Why:** A merged task previously failed with `HOOK_NOT_FOUND` because no path was configured, even though the project had a valid Gradle wrapper.

**How to apply:** Keep the hook idempotent and non-interactive. For Android projects, run SDK-dependent Gradle checks only when a valid SDK directory is available so merges remain usable in SDK-less environments.
# Bulk Label Change – Confluence Data Center Plugin

Rename any label to another label across all pages and blog posts in Confluence. Designed for non-admin users — only content the logged-in user can edit is affected.

## Requirements

| Dependency         | Version       |
|--------------------|---------------|
| Confluence DC      | 9.2.9         |
| JDK                | 17+           |
| Atlassian SDK      | 8.13+         |

## Build

```bash
atlas-mvn clean package

# Skip tests
atlas-mvn clean package -DskipTests
```

The plugin JAR lands in `target/bulk-label-change-1.0.0.jar`.

## Install

1. Go to **⚙ Administration → Manage apps → Upload app** (requires admin).
2. Upload `bulk-label-change-1.0.0.jar`.
3. Confirm the plugin is enabled.

Once installed, every logged-in user sees the feature — no admin rights needed to use it.

## Usage

1. Click **Bulk Label Change** in the top navigation bar.
2. Enter the **source label** (the one to remove).
3. Enter the **target label** (the replacement).
4. Click **Preview** — you'll see every item you can edit that carries the source label. Items you can't edit are counted but skipped.
5. Click **Apply** to execute the rename.

The operation removes the source label and adds the target label on each permitted item. If the target label already exists on an item it won't be duplicated.

## Permission model

The plugin **respects Confluence's existing permissions**. When a user runs a rename:

- Content they have **Edit** permission on → label is changed.
- Content they **cannot edit** → silently skipped and reported as "skipped" in both preview and results.
- No escalation, no impersonation. The operation runs entirely as the logged-in user.

This means the management team can safely rename labels across the spaces they manage without affecting spaces they don't have access to.

## Project structure

```
src/
  main/
    java/…/action/
      BulkLabelChangeAction.java       ← XWork action (UI controller)
    java/…/service/
      BulkLabelChangeService.java      ← Core logic + permission filtering
    resources/
      atlassian-plugin.xml             ← Plugin descriptor
      templates/
        bulk-label-change.vm               ← Form
        bulk-label-change-preview.vm       ← Preview table
        bulk-label-change-results.vm       ← Post-execution summary
  test/
    java/…/
      BulkLabelChangeServiceTest.java  ← Tests including permission checks
```

## Notes

- **Data Center safe.** Uses `LabelManager` which operates through the shared database layer.
- **Irreversible.** There is no undo — the preview step exists for verification.
- Labels are normalised to lowercase, matching Confluence's own behaviour.
- For very large instances, consider wrapping the execute path in a `LongRunningTask` for progress feedback.

## Local development

```bash
atlas-run
# http://localhost:1990/confluence  (admin / admin)
```

## License

MIT

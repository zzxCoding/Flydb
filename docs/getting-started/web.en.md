# Flydb local GUI

English | [中文](web.md)

> Available since Flydb 0.3.5. Use the matching CLI distribution; 0.3.4 and earlier do not include this command.

## Start

Extract the CLI distribution ZIP and run:

```bash
bin/flydb web
bin/flydb web --no-open --port 8317
bin/flydb --config /path/to/project/flydb.conf web
```

On Windows use `bin\flydb.bat web`. Java 8+ and a browser are sufficient. Assets are bundled;
Node.js, internet access and an AI model are unnecessary at runtime. Keep the Flydb process running.
Omit the port to choose a free one. If automatic browser opening fails, copy the complete printed
address. A plain `http://127.0.0.1:port/` URL or bookmark also works in a fresh browser, including Safari.
After restarting on the same port, refresh or select Retry. Use the new address if the port changed.

The workbench has no accounts, login, roles or team administration. The server binds only to
`127.0.0.1`; it is a local interface for configuration and migrations.

## Connect configurations

Use **Add configuration** to import an existing `.conf`, create a project, or discover several
configurations in a selected directory. The working directory resolves relative script paths and
should match the directory used for CLI invocations. Names, groups and environment labels help
identify configurations; labels do not grant permissions. Duplication creates a new file and never
overwrites an existing destination. Removing an entry preserves its configuration and SQL files.
Discovery is bounded; select uncovered subdirectories separately when the result reports limits.

**Connection & settings** provides common connection fields, editable JDBC URLs, migration locations,
password references and all advanced configuration keys. Special vendor URLs remain directly editable.
Both creation and connection settings offer **Oracle**, with port 1521 and a Service name/SID selector.
Existing simple Oracle service/SID URLs populate the form; TNS descriptors and multi-address URLs stay
in direct-edit mode. Formats follow the [Oracle JDBC documentation](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/data-sources-and-URLs.html).
Configuration precedence and options are exactly those of the [CLI reference](../reference/configuration.md).
Expand effective configuration to see the actual values and their sources.

Place an independently obtained, licensed JDBC JAR in the distribution's `drivers/`, or select an
existing driver directory in **Edit name & group**. Maven coordinates, enterprise settings, local
repositories and offline/download policies follow the CLI. A configured download policy may access
the selected repository. Driver files are never bundled or redistributed by this GUI. Successful
inspection records the resolved driver class and source.

Keep `flydb.password=${env:DB_PASSWORD}` or use `flydb.password.file=/path/to/password` when possible.
Environment variables belong to the server process; restart it after changing the shell environment.
The optional session password stays in page memory and disappears on refresh. Preview and execution
must use the same effective credentials. Existing passwords are hidden and never replaced with a mask.

Saving preserves untouched comments, newlines and references. External edits produce a revision
notice. Review differences, choose conflicting values, merge, then save. A second external change
still prevents overwriting the newer file. Switching language preserves drafts.

## Inspect, preview, execute

### Form and complete-file editing

Both creation and connection settings offer **Form editor / File editor**. Common fields and additional
keys share the same Properties document. File mode preserves comments, order, continuations and extra
keys when switching back to the form. The bundled editor supports line numbers, highlighting, search
and replace, undo, key completion, wrapping and `⌘/Ctrl+S`. Location metadata collapses in file mode;
the dialog action bar remains visible.

**Check configuration** checks syntax and Flydb values without connecting to a database. Creation and
saving repeat validation; errors keep the draft. Cancelling unsaved creation asks before discarding it.
External changes open a comparison with the latest file; merge the desired content before saving again.
Every save still checks the revision and atomically replaces the original file.

Explicit file mode displays the original file, including any plaintext credentials already in it.
Drafts stay in page memory and are excluded from run records, reports and browser persistence.
Environment variables and password files are not expanded. Creation reuses the complete template and initialization logic of `flydb init`, producing
`flydb.conf`, `db/migration/V1__init.sql` and a missing `drivers/README.md`. The UI lists these files.
The migration contains a `SELECT 1;` example: replace it with your own SQL before migrating.
Creation itself runs no SQL. Changing the project directory updates an untouched default migration
path while preserving custom paths. Existing configuration or migration files block initialization;
an existing driver guide is kept.

### Database operations

1. **Refresh status** reads database history and scripts; **Validate** checks consistency.
2. **Preview migration** validates, then shows the actual scripts, order, SQL, line numbers and digest.
3. Check the target and confirm. Changed configuration, target, scripts or pending history require a new preview.
4. Follow confirmed statements, elapsed time, last activity and transaction outcome. Post-run verification
   is reported separately from execution success.

Preview does not prove vendor SQL will execute. SQL and Java callbacks retain the Core lifecycle
and are outside the migration preview list; do not replace callback files or driver JARs after review.
Execution uses the migration statements checked while holding the database migration lock.

Closing or refreshing the browser does not cancel the operation. Stopping the Flydb process can
interrupt it. A record without a trustworthy terminal result appears as **Needs verification**;
inspect the database and history before deciding what to do. No write is automatically replayed.

Advanced actions separately explain and confirm baseline, undo and repair. Undo previews the matched
`U__` script. Baseline records an existing version; repair changes history and does not reverse SQL.
“Clean database” removes tables, views, sequences, and Flydb history/lock tables in the connection’s current
schema according to its dialect, including objects not managed by Flydb. Check the displayed target, account
and backups, acknowledge the warning, type `CLEAN`, then select “Confirm clean”. Confirmation is bound to
effective settings, expires after five minutes, and is single-use. The confirmation page does not connect or
enumerate objects; the connection determines the actual schema. Local configuration and SQL files are retained.
Clean is enabled for this invocation only; `flydb.conf` is not changed. Failure may leave partial deletions;
inspect the database before deciding what to do next.

## CLI and Agent interoperability

Run records default to `~/.flydb/workbench`. CLI invocations record even when Web is not running.
**All runs** includes records from the same machine and state directory; per-configuration history
also matches the original file path. To share a custom directory:

```bash
export FLYDB_WORKBENCH_DIR=/path/to/local-state
bin/flydb --config /path/to/flydb.conf info
bin/flydb web
```

If Web uses `--state-dir`, set the same directory for CLI via `FLYDB_WORKBENCH_DIR`. This does not
automatically observe remote machines, older CLI releases or application starters. Existing CLI
`--json` stdout remains a single JSON envelope.

Reports use the selected language and timezone while preserving SQL, paths and original vendor
diagnostics. Passwords, secret URL parameters and configuration values named with password/secret/token/
credential are redacted. Unmarked sensitive business literals cannot be exhaustively inferred;
review reports according to your project's data policy before sharing. The list shows the most recent
200 records; older files remain on disk without automatic deletion.
Project and all-run histories show 10 entries per page, with navigation and direct page entry above and below the list. Background refresh preserves the page; switching projects resets project history to page one.

## Preferences and troubleshooting

“Copy for Agent” appears beside the configuration name across all three tabs, or below it on narrow screens.
It copies file and working-directory paths, redacted connection settings, the latest inspection statistics and up to
50 scripts, plus 10 recent execution summaries and errors. Stale state, excluded unsaved drafts and truncation are explicit.
It does not read raw configuration files or include temporary passwords or SQL bodies. Nothing is automatically sent to an Agent.
Add your specific request when pasting; the Agent needs access to the local paths and must verify current state first.
Copying context does not authorize database writes. A manual-copy dialog is available when clipboard access fails.

Create empty groups in the sidebar; click a group name to collapse it. Its menu supports rename,
move up/down and delete. Drag a group before another group to reorder, or onto Ungrouped to move it last.
Drag configurations into groups, or use the folder button beside a configuration to choose its group.
Deleting a group moves its configurations to Ungrouped and preserves files and database data.
Groups and order persist in the local workbench across restarts. Collapse preferences stay in the browser;
search temporarily expands matching groups.

Use the bottom-left controls for Chinese/English and dark/light themes. Preferences persist in the
browser. Narrow screens use the top-left configuration menu; SQL scrolls within its preview.
Large previews use a read-only editor that renders the visible region. Use `Ctrl/⌘ F` to search the full
SQL and scroll horizontally for long lines. “Download full SQL” exports every preview statement in the
selected script. Migration lists show 10 entries per page with controls above and below the list; preview directories show 50, with full-set version/script
search and direct page-number entry. Filtering and pagination never change totals or the execution set.

- Driver failure: check the JAR, Java version, class, coordinate and offline setting.
- Connection failure: check the effective URL, credentials and network, then inspect again.
- Validation or SQL failure: read the original details and [error reference](../reference/errors.md).
- Unreadable execution record (`UNREADABLE_RECORD`): the local record could not be read; this does not mean the database is empty or the migration failed. Preserve `runs/<report id>/summary.json` and `events.jsonl` in the state directory, check file size, integrity and access permissions, and verify database state before deciding what to do next.
- Unavailable page: keep the local process running, check the port, then refresh or select Retry.
- Configuration conflict: merge external changes before saving; the original file was not overwritten.

See the [design](../design/12-web-workbench.md), [local API](../reference/web-api.md) and
[acceptance evidence](../design/13-web-implementation-plan.md) for implementation and verification details.

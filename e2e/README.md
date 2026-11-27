# Fork Phase 4 CI entry point

`.github/workflows/e2e.yml` runs the current server application test suite on H2 and starts the exact-SHA jar on a fresh PostgreSQL database. The test suite contains the existing merge, lifecycle, progress, backup-format, migration-runner, duplicate, doctor, settings, and feature-off tests, with seeded application fixtures and fake sources where those tests define them.

This workflow is a CI baseline for Phase 4, not proof that every requested scenario is covered. In particular, it does not yet seed the baseline upstream schema on PostgreSQL, snapshot database and download files around every deletion-matrix operation, perform a full backup/restore round trip, run a SyncYomi stub cycle, or report the 5,000-manga performance timings. Those gaps must remain open in the workspace verification report until separate tests cover them.

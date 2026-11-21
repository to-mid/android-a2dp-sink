# R8 boot restore and Bluetooth role switching implementation plan

**Goal:** Fix R7 startup preference restoration and refresh flicker; make active role changes wait for connection and native process shutdown.

**Architecture:** Keep R7 capability selection and system A2DP implementations. Serialize startup and manual changes with the existing root lock. Run boot restoration from a bounded root service.d hook as well as a retrying Android job; the boot ID makes these paths idempotent. Keep status reads separate from mutations and update device rows only when their contents change.

**Tech Stack:** Java 8 sources, Android API 30, shell, JDK 17 toolchain on the supplied Linux server.

1. Add repeatable host regression coverage for boot policy and lifecycle operations. Preserve the existing ABI and adaptation tests.
2. Add a startup hook asset and root installation/removal. Retry boot jobs on errors, guard cancellation, and check enabled state again inside the lock. Delay root boot restore until Android boot is complete; preserve explicit manual choices.
3. Add graceful A2DP disconnect before disable; wait for existing Bluetooth PIDs to exit before enabling a new role. Keep pairing records and existing rollback behavior.
4. Make status refresh silent, coalesce events, retain device views for unchanged data, avoid asynchronous spinner callbacks issuing stale mutations, and surface boot errors in root status.
5. Bump version to 3.0-beta8 / 10. Build on a new server directory using the original R7 signing key and shared toolchain. Run host tests, APK signature verification, diff checks, and package source/patch/retest notes.

Physical verification requires the affected rooted phone and a Windows 11 audio source. Until available, do not claim music playback, reboot restoration, or long-term stability has passed device testing.

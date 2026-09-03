# Make Root Recovery Enrollment One-Time

Status: in progress
Detailed design: ../../../2026-09-03-root-recovery-enrollment-design.md
Implementation plan: ../../../2026-09-03-root-recovery-enrollment.md

Make `--reset-root-pass` replace only the root user with a canonical fully
privileged recovery identity. The generated password may be used once to
enroll an administrator SSH key, after which only that key can issue JWTs.

Preserve every other user and their existing SSH, password, and JWT behavior.

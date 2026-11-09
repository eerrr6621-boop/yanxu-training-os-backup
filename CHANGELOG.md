# Change log

## Unreleased: shared login-verification email (2026-11-08)

- Allow multiple accounts to share one email through first binding, administrator
  maintenance, imported-account preparation and normal verified login.
- Continue identifying accounts by username/password and user ID. Preserve
  account-specific OTPs, audit history, sessions, device trust and access rights.
- Show shared-mailbox information instead of requiring administrators to replace
  an email. Shared recipient throttling remains in force.
- No database migration, account merge or automatic verification. This isolated
  change has not been deployed; validation details are delivered separately.

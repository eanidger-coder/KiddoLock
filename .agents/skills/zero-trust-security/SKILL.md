---
name: zero-trust-security
description: Modern security architecture skill. Focuses on permission scoping, data integrity, and proactive threat mitigation.
---

# Zero Trust Security Skill

## Core Beliefs
1. **Verify Every Request**: Never assume a command or data packet is valid just because it comes from a "known" source (e.g., Firebase, Cloudflare).
2. **Least Privilege**: Only request and hold the specific permissions/tokens required for the immediate task.
3. **Ephemeral Secrets**: Minimize the lifetime of sensitive tokens in memory.

## Procedures
- **Integrity Checks**: Implement checksums or signature verification for settings received via `RemoteCommandHandler`.
- **Permission Guard**: Before calling a privileged API (e.g., `DevicePolicyManager`), verify the app still holds the necessary admin rights.
- **Side-Channel Prevention**: Ensure sensitive data (PINs, child patterns) are cleared from memory as soon as verification is complete.

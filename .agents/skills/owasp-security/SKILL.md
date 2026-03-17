---
name: owasp-security
description: Mandatory security standards for development. Prevents vulnerabilities in auth, persistence, and external data.
---

# OWASP Security Skill

## Core Directives
1. **Data at Rest**: Never store PINs or passwords in plain text. Use encrypted SharedPreferences or DataStore.
2. **Input Validation**: Sanitize all inputs from children/parents to prevent injections.
3. **Permission Scoping**: Request the minimum set of permissions necessary for the current task.
4. **Persistence Audit**: Regularly check if sensitive data is being logged or leaked (leaks on logcat).

## Procedures
- **Security Review**: Run `grep` for common leaky patterns (`System.out`, `Log.d(TAG, "PIN: " + pin)`).
- **Threat Modeling**: Identify the most likely attack vectors (e.g., PIN brute-forcing via automation).

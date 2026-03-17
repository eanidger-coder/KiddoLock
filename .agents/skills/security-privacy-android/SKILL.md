---
name: security-privacy-android
description: Mandatory security and privacy standards for Android development, focusing on 2026 Zero-Trust principles.
---

# Security & Privacy Android (2026)

This skill enforces strict security and privacy standards for Android applications, ensuring data integrity, user trust, and multi-layered protection.

## Core Principles

- **Least Privilege**: Only request the minimum set of permissions required for core functionality.
- **Data Minimization**: Avoid collecting or storing sensitive data unless strictly necessary.
- **Transparency**: Clear indicators to the user when sensitive services (e.g., Accessibility) are active.

## Implementation Standards

### 1. Hardening & Obfuscation
- **ProGuard/R8**: Mandatory obfuscation for all production builds to prevent reverse engineering.
- **Security Provider**: Ensure the GMS Security Provider is up-to-date to protect against SSL/TLS vulnerabilities.
- **Encrypted Storage**: Use `EncryptedSharedPreferences` or `EncryptedFile` for sensitive tokens and keys.

### 2. Permissions & Scoping
- **Scoped Storage**: Strictly follow scoped storage guidelines to prevent unauthorized file access.
- **Runtime Permissions**: Transparently handle permission requests, providing clear rationale to the user.
- **Activity/Service Security**: Ensure all exported components are protected with proper permissions.

### 3. Accessibility & Sensitive APIs
- **Service Protection**: When implementing an `AccessibilityService`, ensure it is protected with the `BIND_ACCESSIBILITY_SERVICE` permission.
- **Data Sanitization**: Never log sensitive user data captured by accessibility services.
- **User Control**: Provide easy-to-access settings for the user to disable or reset monitoring features.

## Interaction Patterns

When writing security-sensitive code:
1. **Threat Model**: Briefly consider potential attack vectors (e.g., PIN brute-forcing).
2. **Review Permissions**: Check if a requested permission is truly necessary.
3. **Audit Trails**: Implement robust logging for security events (e.g., "Failed admin login attempt") while keeping the logs clean of PII.

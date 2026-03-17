---
name: flutter-firebase-integration
description: Best practices for Firebase Auth, Firestore, Cloud Functions, and Analytics integration in Flutter apps (2026 standards).
---

# Flutter Firebase Integration Skill

## Core Dependencies
```yaml
dependencies:
  firebase_core: ^3.0.0
  firebase_auth: ^5.0.0
  cloud_firestore: ^5.0.0
  firebase_messaging: ^15.0.0
  firebase_analytics: ^11.0.0
```

## Authentication Patterns
1. **Multi-provider Auth** — Support Google, Apple, Email/Password. Use `FirebaseAuth.instance.authStateChanges()` stream for reactive UI.
2. **Silent Token Refresh** — Firebase SDK handles this automatically. Never store tokens manually.
3. **Custom Claims** — Use Cloud Functions to set user roles. Check claims client-side with `user.getIdTokenResult()`.

## Firestore Best Practices
1. **Denormalize data** — Duplicate fields across documents for read performance. Firestore charges per read, not per field.
2. **Use subcollections** — For 1-to-many relationships (e.g., `users/{uid}/settings`).
3. **Batch writes** — Use `WriteBatch` for atomic multi-document operations.
4. **Pagination** — Always use `startAfterDocument()` with `limit()`. Never load entire collections.
5. **Offline persistence** — Enabled by default on mobile. Use `source: Source.cache` for offline-first reads.

## Security Rules
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

## Anti-Patterns
- Never use `get()` in a loop — use `where()` queries instead.
- Never store sensitive data (passwords, PII) in Firestore without encryption.
- Never use `collectionGroup()` queries without composite indexes.

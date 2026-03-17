---
name: git-workflow-mastery
description: Git branching strategies, commit conventions, PR workflows, conflict resolution, and release management.
---

# Git Workflow Mastery Skill

## Branching Strategy (GitHub Flow)
1. `main` — Always deployable. Protected branch.
2. `feature/*` — Short-lived feature branches from `main`.
3. `fix/*` — Bug fix branches from `main`.
4. `release/*` — Optional release stabilization branches.

## Commit Convention (Conventional Commits)
```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

Types:
| Type | Use |
|------|-----|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, no logic change |
| `refactor` | Code restructure, no behavior change |
| `perf` | Performance improvement |
| `test` | Adding/fixing tests |
| `chore` | Build, CI, tooling changes |

## PR Workflow
1. Create a branch from `main` with a descriptive name.
2. Make small, focused commits. Each commit should be independently reviewable.
3. Push and open a Pull Request with a clear description.
4. Request review from at least one team member.
5. Address feedback, then squash-merge into `main`.

## Conflict Resolution
1. Always `git pull --rebase` instead of `git merge` for feature branches.
2. If conflicts arise, resolve them locally, test, then force-push (for feature branches only).
3. Never force-push to `main` or shared branches.

## Best Practices
- Write commit messages in the imperative mood: "Add feature" not "Added feature".
- Keep PRs under 400 lines of diff for effective review.
- Use `.gitignore` aggressively — never commit build artifacts, secrets, or IDE config.
- Tag releases with semantic versioning: `v1.2.3`.

# AuthorGram branch migration archive — 2026-08-01

This record preserves the exact Git object identifiers from immediately before the approved Main/Play unification.

| Ref | Archived commit SHA | Purpose |
|---|---|---|
| `dev` | `a78dbacc5532cc6a805ead8a3cae94810e342ba3` | Former beta/development branch before removal |
| `main` | `2cfe176b3802241b61d039ed406054a79a6da33b` | Main state before maintenance-lock commits and unification |
| `play-market` | `0abc2961284b19f65a61dbac49f0a0eb1574599b` | Play state before the passphrase/rebranding update |
| `codex/fix-chat-key-button-functionality` | `d6c005c72240e805acc82305c2cfd316a65d8af6` | Temporary Codex branch before removal |

The archived states can be inspected or restored directly by commit SHA, for example:

```bash
git show a78dbacc5532cc6a805ead8a3cae94810e342ba3
git branch restore-old-dev a78dbacc5532cc6a805ead8a3cae94810e342ba3
```

The final supported branches are `main` and `play-market`.

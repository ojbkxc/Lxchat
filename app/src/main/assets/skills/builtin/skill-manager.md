---
name: skill-manager
description: Manage OpenCode skills with lazy loading - archive, restore, search skills to save context tokens.
---

# Skill Manager

Manage OpenCode skills with lazy loading to save context tokens.

## Commands

```bash
# View statistics
~/.config/opencode/skill-manager.sh stats

# List all skills
~/.config/opencode/skill-manager.sh list

# Restore skill from archive (get full content)
~/.config/opencode/skill-manager.sh restore <skill-name>

# Archive skill (keep minimal index)
~/.config/opencode/skill-manager.sh archive <skill-name>

# Search skills
~/.config/opencode/skill-manager.sh search "<query>"

# Add new skill from path
~/.config/opencode/skill-manager.sh add /path/to/skill
```

## Current Active Skills

```
cli-runner, deep-design, external-delegate, feature-analysis,
git-commit, graphify, harness-init, nano-brain, systematic-debugging
```

## How It Works

1. **Session start**: Only minimal index files are loaded (~36KB vs ~14MB)
2. **When using skill**: `skill(name="deep-design")` loads minimal file
3. **Need full content**: `restore` skill from archive, then reload

## Workflow

When you need a skill that's archived:

1. Check if skill exists: `~/.config/opencode/skill-manager.sh list-archived`
2. Restore it: `~/.config/opencode/skill-manager.sh restore <skill-name>`
3. Use the skill normally
4. Optionally archive again: `~/.config/opencode/skill-manager.sh archive <skill-name>`

## Files

- `~/.config/opencode/skill-manager.sh` - Main script
- `~/.config/opencode/skills/` - Active skills (minimal indexes)
- `~/.config/opencode/skills-archive/` - Archived skills (full content)
- `~/.config/opencode/SKILL-MANAGER-README.md` - Full documentation

#!/usr/bin/env python3
"""
Build builtin skills and plugins index for LxChat.

Scans all cloned skill-market repos, extracts SKILL.md frontmatter,
creates a compact JSON index, and copies a curated subset of SKILL.md
files + all plugin.json files into app/src/main/assets/.
"""

import json
import os
import re
import shutil
from pathlib import Path

# ── Paths ─────────────────────────────────────────────────────
LXCHAT_ROOT = Path(r"C:\GitHub\Lxchat")
ASSETS = LXCHAT_ROOT / "app" / "src" / "main" / "assets"
SKILLS_OUT = ASSETS / "skills" / "builtin"
PLUGINS_OUT = ASSETS / "plugins" / "builtin"
INDEX_OUT = ASSETS / "builtin-skills-index.json"

REPOS = [
    ("skill-manager", Path(r"C:\GitHub\ai\skill-markets\skill-manager\skills")),
    ("ia", Path(r"C:\GitHub\ai\skill-markets\ia")),
    ("agent-skills-hub", Path(r"C:\GitHub\ai\skillF-markets\agent-skills-hub\skills")),
]

PLUGIN_MARKETPLACE = Path(r"C:\GitHub\ai\skill-markets\opencode-plugin-marketplace\plugins")

# ── Curated skills (most useful for an Android AI chat app) ───
CURATED_SKILLS = {
    # Development
    "python-pro", "typescript-pro", "rust-pro", "golang-pro", "java-pro",
    "javascript-pro", "csharp-pro", "cpp-pro", "scala-pro", "ruby-pro",
    "php-pro", "elixir-pro", "haskell-pro", "julia-pro",
    # Testing
    "test-driven-development", "testing-patterns", "test-automator",
    "unit-testing-test-generate", "e2e-testing-patterns",
    # Code quality
    "clean-code", "code-reviewer", "code-review-checklist",
    "systematic-debugging", "debugging-strategies", "find-bugs",
    "code-refactoring-refactor-clean", "codebase-cleanup-tech-debt",
    # Security
    "api-security-best-practices", "vulnerability-scanner",
    "memory-safety-patterns", "xss-html-injection",
    "wordpress-penetration-testing", "pentest-checklist",
    # Architecture
    "architecture", "architecture-patterns", "microservices-patterns",
    "api-design-principles", "database-design", "event-sourcing-architect",
    # Workflow
    "brainstorming", "writing-plans", "executing-plans",
    "verification-before-completion", "using-git-worktrees",
    "finishing-a-development-branch", "requesting-code-review",
    # Documentation
    "writing-skills", "code-documentation-doc-generate",
    "documentation-templates", "readme",
    # Frontend/Mobile
    "frontend-design", "ui-ux-pro-max", "react-patterns",
    "tailwind-design-system", "flutter-expert", "mobile-developer",
    # DevOps
    "docker-expert", "kubernetes-architect", "terraform-specialist",
    "github-actions-templates", "vercel-deployment",
    # AI/ML
    "prompt-engineering", "rag-implementation", "ai-engineer",
    "llm-app-patterns", "prompt-engineering-patterns",
    # Skill management
    "skill-creator", "skill-manager", "comprehensive-feature-builder",
    # i18n
    "i18n-localization",
}

# ── Frontmatter parser ────────────────────────────────────────
def parse_frontmatter(content):
    """Extract YAML frontmatter (name, description) from SKILL.md."""
    m = re.match(r'^---\s*\n(.*?)\n---\s*\n', content, re.DOTALL)
    if not m:
        return {}, content
    fm_text = m.group(1)
    body = content[m.end():]
    meta = {}
    for line in fm_text.split('\n'):
        km = re.match(r'^(\w[\w_-]*)\s*:\s*(.*)$', line)
        if km:
            key = km.group(1).strip()
            val = km.group(2).strip().strip('"').strip("'")
            meta[key] = val
    return meta, body

# ── Scan all SKILL.md files ───────────────────────────────────
def scan_skills():
    """Scan all repos for SKILL.md, return list of skill entries."""
    entries = []
    for repo_name, skills_dir in REPOS:
        if not skills_dir.exists():
            continue
        for skill_md in skills_dir.rglob("SKILL.md"):
            try:
                content = skill_md.read_text(encoding='utf-8', errors='replace')
            except Exception:
                continue
            meta, body = parse_frontmatter(content)
            name = meta.get('name', skill_md.parent.name)
            description = meta.get('description', '')
            rel_path = str(skill_md.relative_to(skills_dir.parent))
            size_kb = round(len(content.encode('utf-8')) / 1024, 1)
            entries.append({
                'name': name,
                'description': description[:200],  # truncate for index
                'repo': repo_name,
                'path': rel_path.replace('\\', '/'),
                'sizeKb': size_kb,
                'curated': name in CURATED_SKILLS,
            })
    return entries

# ── Scan all plugin.json files ────────────────────────────────
def scan_plugins():
    """Scan plugin marketplace for .plugin.json files."""
    entries = []
    if not PLUGIN_MARKETPLACE.exists():
        return entries
    for pj in PLUGIN_MARKETPLACE.glob("*.plugin.json"):
        try:
            data = json.loads(pj.read_text(encoding='utf-8'))
        except Exception:
            continue
        entries.append({
            'name': data.get('name', pj.stem),
            'displayName': data.get('displayName', ''),
            'description': data.get('description', '')[:200],
            'categories': data.get('categories', []),
            'license': data.get('license', ''),
            'maintained': data.get('maintained', False),
            'lastUpdated': data.get('lastUpdated', ''),
            'fileName': pj.name,
        })
    return entries

# ── Main ──────────────────────────────────────────────────────
def main():
    print("Scanning SKILL.md files...")
    skills = scan_skills()
    print(f"  Found {len(skills)} skills across {len(REPOS)} repos")

    print("Scanning plugin.json files...")
    plugins = scan_plugins()
    print(f"  Found {len(plugins)} plugins")

    # Create output dirs
    SKILLS_OUT.mkdir(parents=True, exist_ok=True)
    PLUGINS_OUT.mkdir(parents=True, exist_ok=True)

    # Copy curated SKILL.md files
    curated_count = 0
    curated_size = 0
    for skill in skills:
        if not skill['curated']:
            continue
        # Find the source file
        for repo_name, skills_dir in REPOS:
            if repo_name != skill['repo']:
                continue
            src = skills_dir.parent / skill['path']
            if src.exists():
                dst = SKILLS_OUT / f"{skill['name']}.md"
                shutil.copy2(src, dst)
                curated_count += 1
                curated_size += src.stat().st_size
                break
    print(f"  Copied {curated_count} curated SKILL.md files ({round(curated_size/1024, 1)} KB)")

    # Copy all plugin.json files
    plugin_count = 0
    plugin_size = 0
    if PLUGIN_MARKETPLACE.exists():
        for pj in PLUGIN_MARKETPLACE.glob("*.plugin.json"):
            dst = PLUGINS_OUT / pj.name
            shutil.copy2(pj, dst)
            plugin_count += 1
            plugin_size += pj.stat().st_size
    print(f"  Copied {plugin_count} plugin.json files ({round(plugin_size/1024, 1)} KB)")

    # Write index JSON
    index = {
        'version': 1,
        'skills': skills,
        'plugins': plugins,
    }
    INDEX_OUT.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    index_size = INDEX_OUT.stat().st_size
    print(f"  Wrote index: {INDEX_OUT.name} ({round(index_size/1024, 1)} KB)")

    total_kb = round((curated_size + plugin_size + index_size) / 1024, 1)
    print(f"\nTotal assets added: {total_kb} KB")
    print(f"  Skills: {len(skills)} indexed, {curated_count} curated (offline)")
    print(f"  Plugins: {plugin_count} (offline)")
    print(f"  Index: {round(index_size/1024, 1)} KB (all entries)")

if __name__ == '__main__':
    main()
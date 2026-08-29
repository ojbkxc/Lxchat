---
name: comprehensive-feature-builder
description: Systematic 5-phase workflow for researching, designing, implementing, and testing ANY feature. Integrates explore/librarian agents, GraphQL inspector, Sequential Thinking, UI/UX Pro Max, OpenSpec proposals, and E2E test generator with Playwright MCP. Saves checkpoints to files to prevent token bloat. Loops on test failures until all pass.
---

# Comprehensive Feature Builder Skill

> **Purpose**: A systematic, checkpoint-based workflow for researching, designing, implementing, and testing ANY feature with full traceability.

## Overview

This skill provides a 5-phase workflow that:
1. **Saves reports to files** at each phase (prevents token bloat)
2. **Uses checklists** for progress tracking
3. **Integrates OpenSpec** for proposal creation
4. **Uses E2E test generator** with Playwright MCP for verification
5. **Captures screenshots** at every step
6. **Loops on failures** until tests pass

## When to Use

Trigger phrases:
- "Implement feature X comprehensively"
- "Research and build X"
- "Full feature implementation for X"
- "Create X with full testing"

## Prerequisites

Before starting:
- [ ] Identify feature name (kebab-case): `{feature-name}`
- [ ] Create output directory: `/ai/feature-research/{feature-name}/`
- [ ] Verify backend is running (GraphQL endpoint accessible)
- [ ] Verify frontend dev server is running

## Directory Structure

```
/ai/feature-research/{feature-name}/
├── progress.json                    # Current phase & status
├── 01-discovery/
│   ├── checklist.md                 # Phase 1 checklist
│   ├── report.md                    # Discovery findings
│   ├── codebase-analysis.md         # Explore agent results
│   ├── external-research.md         # Librarian agent results
│   └── graphql-schema.md            # GraphQL inspector results
├── 02-analysis/
│   ├── checklist.md                 # Phase 2 checklist
│   ├── report.md                    # Gap analysis
│   ├── architecture-decisions.md    # Oracle consultation
│   └── sequential-thinking.md       # Structured analysis
├── 03-design/
│   ├── checklist.md                 # Phase 3 checklist
│   ├── report.md                    # Design summary
│   ├── ui-concept.md                # UI/UX Pro Max output
│   ├── component-specs.md           # Component hierarchy
│   └── ui-prompts.md                # Implementation prompts
├── 04-implementation/
│   ├── checklist.md                 # Phase 4 checklist
│   ├── report.md                    # Implementation summary
│   ├── openspec-proposal/           # OpenSpec change proposal
│   └── code-changes.md              # Files modified
├── 05-testing/
│   ├── checklist.md                 # Phase 5 checklist
│   ├── report.md                    # Test results
│   ├── e2e-tests/                   # Generated test files
│   ├── screenshots/                 # Step-by-step screenshots
│   └── bug-fixes.md                 # Bugs found and fixed
└── final-summary.md                 # Complete feature summary
```

---

## Phase 1: Discovery

### Objective
Gather all context about the feature from codebase, PRD, GraphQL schema, and external research.

### Checklist (`01-discovery/checklist.md`)

```markdown
## Phase 1: Discovery Checklist

### 1.1 Codebase Exploration
- [ ] Launch explore agent for existing implementations
- [ ] Search for related pages, components, stores, hooks
- [ ] Identify existing patterns to follow
- [ ] Save results to `codebase-analysis.md`

### 1.2 PRD & Requirements
- [ ] Search PRD for feature requirements
- [ ] Find user stories and acceptance criteria
- [ ] Identify related epics and dependencies
- [ ] Document in `report.md`

### 1.3 GraphQL Schema Inspection
- [ ] Use graphql-tools MCP to inspect types
- [ ] List all related queries and mutations
- [ ] Document input/output types
- [ ] Save to `graphql-schema.md`

### 1.4 External Research (if needed)
- [ ] Launch librarian agent for best practices
- [ ] Research UI/UX patterns
- [ ] Find library documentation
- [ ] Save to `external-research.md`

### 1.5 Phase Completion
- [ ] All sub-checklists complete
- [ ] `report.md` summarizes findings
- [ ] Update `progress.json` to phase 2
```

### Actions

```typescript
// 1. Fire parallel background agents
task(subagent_type="explore", run_in_background=true, load_skills=[], 
  description="Explore {feature} in codebase",
  prompt="Find all code related to {feature}: pages, components, stores, hooks, GraphQL operations...")

task(subagent_type="explore", run_in_background=true, load_skills=[],
  description="Explore backend schema for {feature}",
  prompt="Find all backend GraphQL types, queries, mutations for {feature}...")

task(subagent_type="librarian", run_in_background=true, load_skills=[],
  description="Research {feature} best practices",
  prompt="Find UI/UX best practices, patterns, and examples for {feature}...")

// 2. Use GraphQL inspector MCP
graphql-tools_filter_types(search="{feature}", detailed=false)
graphql-tools_filter_queries(search="{feature}", detailed=false)
graphql-tools_filter_mutations(search="{feature}", detailed=false)
// Then get details for specific types found

// 3. Search PRD
grep(pattern="Epic.*{feature}|Story.*{feature}", path="/ai/")

// 4. Collect background results
background_output(task_id="...")

// 5. Save reports to files
write(filePath="/ai/feature-research/{feature}/01-discovery/report.md", content="...")
```

### Output Template (`01-discovery/report.md`)

```markdown
# Discovery Report: {Feature Name}

**Date**: {date}
**Phase**: 1 - Discovery
**Status**: Complete

## Executive Summary
{1-2 paragraph summary of findings}

## Existing Implementation
- **Pages**: {list of existing pages}
- **Components**: {list of components}
- **Stores**: {Zustand stores}
- **Hooks**: {custom hooks}
- **GraphQL Operations**: {queries, mutations}

## PRD Requirements
- **Epic**: {epic number and name}
- **Stories**: {list of user stories}
- **Acceptance Criteria**: {key AC}

## GraphQL Schema
### Types
{list of types with key fields}

### Queries
{list of queries with arguments}

### Mutations
{list of mutations with inputs}

## External Research
{key findings from librarian agent}

## Gaps Identified
- {gap 1}
- {gap 2}

## Next Phase
Ready for Phase 2: Analysis
```

---

## Phase 2: Analysis

### Objective
Analyze gaps, make architecture decisions, and plan implementation using Sequential Thinking.

### Checklist (`02-analysis/checklist.md`)

```markdown
## Phase 2: Analysis Checklist

### 2.1 Gap Analysis
- [ ] Compare PRD requirements vs existing implementation
- [ ] Identify missing features
- [ ] Identify bugs or issues
- [ ] Document in `report.md`

### 2.2 Sequential Thinking
- [ ] Use Sequential-Thinking MCP for structured analysis
- [ ] Break down implementation into steps
- [ ] Identify dependencies and order
- [ ] Save to `sequential-thinking.md`

### 2.3 Architecture Decisions (if complex)
- [ ] Consult Oracle for architecture questions
- [ ] Document decisions and rationale
- [ ] Save to `architecture-decisions.md`

### 2.4 Phase Completion
- [ ] Gap analysis complete
- [ ] Implementation plan created
- [ ] Update `progress.json` to phase 3
```

### Actions

```typescript
// 1. Use Sequential Thinking for structured analysis
Sequential-Thinking_sequentialthinking(
  thought="Analyzing {feature} implementation...",
  thoughtNumber=1,
  totalThoughts=5,
  nextThoughtNeeded=true
)

// 2. Consult Oracle if complex architecture decisions needed
task(subagent_type="oracle", run_in_background=false, load_skills=[],
  description="Architecture consultation for {feature}",
  prompt="Review the discovery report and advise on: {specific questions}...")

// 3. Save analysis report
write(filePath="/ai/feature-research/{feature}/02-analysis/report.md", content="...")
```

---

## Phase 3: Design

### Objective
Create UI/UX design using ui-ux-pro-max skill, define component specs.

### Checklist (`03-design/checklist.md`)

```markdown
## Phase 3: Design Checklist

### 3.1 UI/UX Design
- [ ] Load ui-ux-pro-max skill
- [ ] Create UI concept based on research
- [ ] Define visual style and patterns
- [ ] Save to `ui-concept.md`

### 3.2 Component Specification
- [ ] Define component hierarchy
- [ ] Specify props and state
- [ ] Define data flow
- [ ] Save to `component-specs.md`

### 3.3 UI Prompts
- [ ] Create detailed implementation prompts
- [ ] Include Vietnamese text requirements
- [ ] Include accessibility requirements
- [ ] Save to `ui-prompts.md`

### 3.4 Phase Completion
- [ ] UI concept approved
- [ ] Component specs complete
- [ ] Update `progress.json` to phase 4
```

### Actions

```typescript
// 1. Load UI/UX Pro Max skill
skill("ui-ux-pro-max")

// 2. Create UI concept
task(category="visual-engineering", load_skills=["ui-ux-pro-max", "frontend-ui-ux"],
  description="Design UI for {feature}",
  prompt="Based on discovery report, create UI concept for {feature}...")

// 3. Save design documents
write(filePath="/ai/feature-research/{feature}/03-design/ui-concept.md", content="...")
write(filePath="/ai/feature-research/{feature}/03-design/component-specs.md", content="...")
```

---

## Phase 4: Implementation

### Objective
Create OpenSpec proposal, implement code changes, verify with LSP diagnostics.

### Checklist (`04-implementation/checklist.md`)

```markdown
## Phase 4: Implementation Checklist

### 4.1 OpenSpec Proposal
- [ ] Create change directory: `openspec/changes/{change-id}/`
- [ ] Write `proposal.md` with Why/What/Impact
- [ ] Write `tasks.md` with implementation steps
- [ ] Write spec deltas in `specs/{capability}/spec.md`
- [ ] Validate: `openspec validate {change-id} --strict --no-interactive`
- [ ] Copy proposal to `04-implementation/openspec-proposal/`

### 4.2 Code Implementation
- [ ] Implement tasks from `tasks.md` sequentially
- [ ] Mark each task complete as done
- [ ] Run `pnpm codegen` if GraphQL changes
- [ ] Verify with `lsp_diagnostics` on changed files

### 4.3 Build Verification
- [ ] Run `pnpm build` - must pass
- [ ] Fix any TypeScript errors
- [ ] Document changes in `code-changes.md`

### 4.4 Phase Completion
- [ ] All tasks in `tasks.md` marked complete
- [ ] Build passes
- [ ] Update `progress.json` to phase 5
```

### Actions

```typescript
// 1. Create OpenSpec proposal
bash("mkdir -p openspec/changes/{change-id}/specs/{capability}")

write(filePath="openspec/changes/{change-id}/proposal.md", content=`
# Change: {Feature Name}

## Why
{Problem/opportunity from discovery}

## What Changes
- {Change 1}
- {Change 2}

## Impact
- Affected specs: {capabilities}
- Affected code: {files}
`)

write(filePath="openspec/changes/{change-id}/tasks.md", content=`
## 1. Implementation
- [ ] 1.1 {Task 1}
- [ ] 1.2 {Task 2}
...
`)

write(filePath="openspec/changes/{change-id}/specs/{capability}/spec.md", content=`
## ADDED Requirements
### Requirement: {Feature}
{Description}

#### Scenario: {Success case}
- **WHEN** {action}
- **THEN** {result}
`)

// 2. Validate proposal
bash("openspec validate {change-id} --strict --no-interactive")

// 3. Implement code (delegate to coding agent)
task(category="quick", load_skills=["frontend-ui-ux"],
  description="Implement {feature}",
  prompt="Implement the following from tasks.md: ...")

// 4. Verify
lsp_diagnostics(filePath="{changed-file}", severity="error")
bash("cd sharex-frontend-v0 && pnpm build")
```

---

## Phase 5: Testing

### Objective
Generate E2E tests, run with Playwright MCP, capture screenshots, fix bugs until all pass.

### Checklist (`05-testing/checklist.md`)

```markdown
## Phase 5: Testing Checklist

### 5.1 E2E Test Generation
- [ ] Load e2e-test-generator skill
- [ ] Generate test cases from acceptance criteria
- [ ] Create page objects if needed
- [ ] Save tests to `e2e-tests/`

### 5.2 Test Execution with Playwright MCP
- [ ] Navigate to feature page
- [ ] Execute each test step
- [ ] Capture screenshot after each action
- [ ] Log all steps to `report.md`

### 5.3 Bug Fix Loop
- [ ] If test fails, document bug in `bug-fixes.md`
- [ ] Fix the bug
- [ ] Re-run test
- [ ] Repeat until pass
- [ ] Capture final passing screenshot

### 5.4 Phase Completion
- [ ] All E2E tests pass
- [ ] All screenshots captured
- [ ] Bug fixes documented
- [ ] Update `progress.json` to complete
```

### Actions

```typescript
// 1. Load E2E test generator skill
skill("e2e-test-generator")

// 2. Generate tests based on acceptance criteria
task(category="quick", load_skills=["e2e-test-generator", "playwright"],
  description="Generate E2E tests for {feature}",
  prompt="Generate E2E tests for {feature} based on acceptance criteria: ...")

// 3. Execute tests with Playwright MCP
playwright_browser_navigate(url="http://localhost:5123/{feature-page}")
playwright_browser_snapshot()
playwright_browser_take_screenshot(filename="/ai/feature-research/{feature}/05-testing/screenshots/01-initial.png")

// For each test step:
playwright_browser_click(ref="{element-ref}", element="{description}")
playwright_browser_take_screenshot(filename="/ai/feature-research/{feature}/05-testing/screenshots/02-after-click.png")

// 4. Bug fix loop
// IF test fails:
//   - Document bug in bug-fixes.md
//   - Fix the code
//   - Re-run test
//   - Repeat until pass

// 5. Save test report
write(filePath="/ai/feature-research/{feature}/05-testing/report.md", content=`
# E2E Test Report: {Feature}

## Test Results
| Test | Status | Screenshot |
|------|--------|------------|
| {test 1} | ✅ PASS | screenshots/01-xxx.png |
| {test 2} | ✅ PASS | screenshots/02-xxx.png |

## Bugs Found & Fixed
| Bug | Fix | Verified |
|-----|-----|----------|
| {bug 1} | {fix} | ✅ |

## Screenshots
{list of all screenshots with descriptions}
`)
```

---

## Progress Tracking

### progress.json Schema

```json
{
  "feature": "{feature-name}",
  "currentPhase": 1,
  "phases": {
    "1-discovery": {
      "status": "complete",
      "completedAt": "2026-02-13T10:00:00Z",
      "report": "01-discovery/report.md"
    },
    "2-analysis": {
      "status": "in-progress",
      "startedAt": "2026-02-13T10:30:00Z"
    },
    "3-design": { "status": "pending" },
    "4-implementation": { "status": "pending" },
    "5-testing": { "status": "pending" }
  },
  "lastUpdated": "2026-02-13T10:30:00Z"
}
```

### Resume Instructions

When resuming work:
1. Read `progress.json` to find current phase
2. Read the current phase's `checklist.md` to find incomplete items
3. Read previous phase's `report.md` for context
4. Continue from the first unchecked item

---

## Tool Reference

| Phase | Tools | Purpose |
|-------|-------|---------|
| 1 | explore agent, librarian agent, graphql-tools MCP, grep | Gather context |
| 2 | Sequential-Thinking MCP, oracle agent | Analyze and plan |
| 3 | ui-ux-pro-max skill, visual-engineering category | Design UI |
| 4 | OpenSpec CLI, coding agents, lsp_diagnostics | Implement |
| 5 | e2e-test-generator skill, playwright MCP | Test and verify |

---

## Quick Start

```bash
# 1. Create feature directory
FEATURE="meal-planning-enhancement"
mkdir -p /ai/feature-research/$FEATURE/{01-discovery,02-analysis,03-design,04-implementation,05-testing/screenshots}

# 2. Initialize progress.json
echo '{"feature":"'$FEATURE'","currentPhase":1,"phases":{"1-discovery":{"status":"in-progress"},"2-analysis":{"status":"pending"},"3-design":{"status":"pending"},"4-implementation":{"status":"pending"},"5-testing":{"status":"pending"}}}' > /ai/feature-research/$FEATURE/progress.json

# 3. Start Phase 1: Discovery
# (Follow checklist above)
```

---

## Anti-Patterns

| Don't | Do Instead |
|-------|------------|
| Skip phases | Complete each phase fully |
| Forget screenshots | Capture after EVERY action |
| Ignore test failures | Fix and re-test until pass |
| Skip OpenSpec | Always create proposal first |
| Manual testing only | Use Playwright MCP for E2E |
| Lose context | Save reports to files |

---

## Success Criteria

Feature is complete when:
- [ ] All 5 phase checklists are 100% complete
- [ ] All E2E tests pass
- [ ] All screenshots captured
- [ ] OpenSpec proposal validated
- [ ] Build passes without errors
- [ ] `final-summary.md` written

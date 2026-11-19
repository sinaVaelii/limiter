# AGENTS.md — Next Release Specification: Monk Mode & Punishment Engine

## 1. Project Context & Operational Mode
- **Mode:** Incremental, verified agentic coding over an existing codebase.
- **Target Features for Next Release:**
  1. **Monk Mode:** User selects distracting apps in a dedicated flow/UI. Selected apps are restricted/limited during a **fixed** time window **00:00–20:00 local device time** (inclusive of 00:00, exclusive of 20:00 unless the existing limiter's convention differs — in which case, document and match the existing convention in Section 2). Configurability of this window is **out of scope** for this release.
  2. **Punishment Mode:** Applies **exclusively** to Telegram and Telegraph. Continuous usage of **60 minutes** triggers a punishment cooldown of **10–20 minutes**, **explicitly configured by the user**. No hidden default is permitted.

---

## 2. Hard Rules (Anti-Hallucination & Strict Grounding)
> **VIOLATION OF ANY HARD RULE INVALIDATES THE STEP.**

1. **Zero File/Symbol Hallucination.** Never reference a file path, class, function, state key, DB schema, permission, or API you have not read in the current session. Read before referencing.
2. **No Silent Defaults on Punishment Config.** Do NOT hardcode any fallback for (a) the 60-minute continuous-usage threshold, (b) the punishment duration inside the 10–20 minute range, or (c) the "continuous usage" gap-tolerance. Expose explicit config fields; validate range; if unconfigured, punishment MUST be disabled and the UI MUST surface a "configure punishment" prompt.
3. **Strict Scope Lock.** Modify only files required for the active Section. No unrelated refactors, renames, or rewrites.
4. **No Fake Completion.** Never claim tests pass, builds succeed, or timers/background triggers work unless verified against real code paths and existing project conventions. If you cannot run something, say so explicitly.
5. **Ambiguity Stop Rule.** If an existing convention, package name, scheduler/alarm mechanism, or UI state pattern is unclear or conflicting, **STOP** and ask concise, targeted questions instead of guessing.
6. **Checkpoint Per Section.** At the end of every section, list the exact files changed and the exact diff summary, so the user can revert that section in isolation.

---

## 3. Mandatory Execution Protocol (Every Section)
1. **Inspect** — read only the relevant existing files before proposing edits.
2. **Plan** — list exact files to touch and exact behaviors to add.
3. **Implement** — minimal, idiomatic changes matching existing codebase conventions.
4. **Report** — output the structured Work Report.
5. **Gate** — ask the section's Mandatory Questions and **HALT**. Do NOT proceed until the user explicitly approves.

---

## 4. Mandatory Work Report Template

```markdown
### Work Report — Section [X]
- **Status:** [Completed | Blocked | Needs Clarification]
- **Files Inspected:** [exact paths]
- **Files Created / Modified:** [exact paths + one-line diff summary each]
- **Implemented Logic Summary:** [factual, concise]
- **Unverified / Edge-Case Risks:** [real edge cases]
- **Hard Rules Check:** [PASS/FAIL for rules 1–6, with one-line justification each]

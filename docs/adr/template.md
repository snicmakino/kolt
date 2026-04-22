---
status: proposed
date: YYYY-MM-DD
---

# ADR NNNN: {short title naming the decision, not the problem}

<!--
  House rules:
  - English only.
  - `status`: proposed | accepted | implemented | rejected | deprecated | superseded by ADR-NNNN
    `implemented` means the decision has shipped, not just been agreed to.
  - `date` is the last update, not the original draft date.
  - Drop any section below that doesn't earn its keep. Keep `## Summary` and `## Decision Outcome`.
  - For single-option ADRs (policy / rollout), collapse `## Considered Options` and
    `## Pros and Cons of the Options` into a tail `## Alternatives considered` section.

  Review checklist (run before moving `status` from `proposed` to `accepted`):
  - **No redundancy.** Every sentence earns its place. Cut restatement, rhetorical hedging,
    paraphrased policy references, and "it's worth noting that" phrasing. Imperative voice,
    one load-bearing point per sentence. If Context re-states Summary, or Alternatives
    re-argue what Decision already established, point one at the other instead of duplicating.
  - **Future-decision sufficiency.** List the next foreseeable cases that will touch this
    decision (the third daemon, the fourth target, the next schema bump) and verify each
    is answerable from this ADR alone. If a contributor would have to ask or read git
    history to proceed, the ADR is under-specified — close the gap before accepting.
    Watch for open vocabularies (e.g. `<role>`) without a rule for admitting new members.
  - **No ambiguity.** Read each sentence and ask whether a new reader could take it two
    ways. Flag undefined terms, dangling cross-references ("see §3" with no §3), shorthand
    used without expansion, and set-membership notation (`∈`) used with open-ended sets.
  - **Cross-ADR consistency.** Claims about "prior ADRs are edited / not edited" must match
    what the landing PR actually does. If this ADR supersedes, renames, or narrows an
    earlier one, say so explicitly in both places (or in `Related`).
-->

## Summary

<!--
  5–7 bullets. Each bullet states one load-bearing decision in 2–3 sentences and
  ends with a `(§N)` pointer to the matching subsection in `## Decision Outcome`.
  A reader who only reads this section should know what was decided.
-->

- {Decision 1 in one or two sentences} (§1)
- {Decision 2} (§2)
- {Decision 3} (§3)
- {Decision 4} (§4)
- {Decision 5} (§5)

## Context and Problem Statement

<!--
  What is the situation that forced a decision? What constraint, incident, or
  upstream change makes the status quo untenable? Two to four paragraphs.
  Link the spike report or benchmark that motivated the ADR if one exists.
-->

{Describe the problem in plain prose. State the question the ADR answers.}

## Decision Drivers

<!--
  The criteria the chosen option had to satisfy. These are the hooks a future
  reviewer uses to ask "does this decision still hold?". Keep them concrete and
  testable; avoid generic virtues like "maintainability".
-->

- {Driver 1 — e.g. "warm build wall time stays under 1s for ≤20 files"}
- {Driver 2 — e.g. "no new runtime dependency outside kotlin-* artifacts"}
- {Driver 3}

## Considered Options

<!--
  Name each option in one line. Detailed comparison goes in `## Pros and Cons`.
  Two to four options is typical; one option means this should be the simplified
  form (drop this section and put rejected approaches in `## Alternatives considered`).
-->

- **Option A** — {one-line description}
- **Option B** — {one-line description}
- **Option C** — {one-line description}

## Decision Outcome

Chosen option: **{Option X}**, because {one-sentence justification tied to the drivers}.

### §1 {first decision subsection}

{What is being committed to. Include code snippets, schema, or wire formats
when they are load-bearing. Reference the spike report for measurements
rather than restating numbers here.}

### §2 {second decision subsection}

{...}

### §3 {third decision subsection}

{...}

### Consequences

**Positive**
- {What gets easier or unblocked}
- {What invariant this establishes}

**Negative**
- {What gets harder or constrained}
- {Any deferred follow-up the reader should know is coming}

### Confirmation

<!--
  How will we know the decision is being followed? Code review checklist item,
  test, lint, CI gate, scheduled ADR re-review, or "obvious from the diff".
  If the answer is "trust me", that is fine — say so explicitly.
-->

{e.g. "Schema validation in `KoltTomlParser`; rejected via parse-error tests in
`KoltTomlParserTest.kt`. ADR text and parser stay in sync via PR review."}

## Pros and Cons of the Options

### Option A — {name}

- Good, because {...}
- Good, because {...}
- Bad, because {...}
- Neutral, because {...}

### Option B — {name}

- Good, because {...}
- Bad, because {...}

### Option C — {name}

- Good, because {...}
- Bad, because {...}

<!--
  Simplified form (use when only one realistic option existed):

  ## Alternatives considered

  1. **{Alternative 1}.** Rejected. {One-paragraph reason.}
  2. **{Alternative 2}.** Rejected. {One-paragraph reason.}
-->

## Related

<!--
  Links only — issues, PRs, other ADRs, README sections, architecture.md, spike
  reports. One bullet per reference with a short note on the relationship.
-->

- #NNN — {tracking issue / PR}
- ADR NNNN — {what it shares or supersedes with this one}
- `path/to/spike/REPORT.md` — {measurement source if applicable}
- README — {section name, if this ADR changes user-visible surface}
- `architecture.md` — {section name, if this ADR changes the architecture map}

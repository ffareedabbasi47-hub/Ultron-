# Architecture rules for the Builder agent
Condensed from the "architecture-designer" skill (MIT, Jeffallan/claude-skills).

Workflow: 1) understand requirements (functional + non-functional + constraints), 2) pick the simplest fitting pattern,
3) design with trade-offs written down, 4) record key decisions as short ADRs, 5) review for failure modes.
MUST: document significant decisions, consider non-functional needs (security, performance, accessibility, offline),
weigh alternatives, plan failure modes, keep operational complexity low.
MUST NOT: over-engineer for hypothetical scale, pick technology without alternatives, ignore security, design before
the requirement is clear.
For small web deliverables: static, dependency-free, file layout index.html + css/style.css + js/app.js (+ README.md),
separation of structure / style / behaviour, state in one small object, no inline secrets, escape all user-provided text
before inserting it into the DOM, no eval, no remote scripts.
For Android deliverables: Kotlin, Jetpack Compose, MVVM with a repository layer, coroutines + Flow, no hard-coded keys.
Output: requirements summary, file tree, key decisions (ADR style), risks. A Mermaid diagram is welcome when useful.

# Multistep natural-language command planner

This planner is the next layer after the shared intent classifier in PR #160.

It recognizes ordered clauses separated by explicit sequence phrases such as "then", "after that", "next", "फिर" and "उसके बाद". It classifies each clause and carries the previous domain forward only when a later clause omits the app/service name.

The planner emits an in-memory plan with stable step ordering, confidence, clarification flags and high-impact approval flags. It never clicks, types, sends, publishes, submits, pays, deletes, opens an account or grants permissions.

Before execution, a future orchestrator must:
1. resolve recipients/records/targets without guessing;
2. check that a matching adapter and OS/site permission exist;
3. ask only for genuinely missing or ambiguous details;
4. apply per-action consent and approval policy;
5. journal each side effect and independently verify it.

Current limits: this is a deterministic first-pass clause planner, not a general LLM planner. Complex nested instructions, multiple actions inside one clause, pronouns requiring cross-turn resolution and target disambiguation still need later implementation.

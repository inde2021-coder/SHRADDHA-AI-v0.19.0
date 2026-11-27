# Command execution preflight gate

This layer connects the ordered plan (PR #163) to runtime adapter discovery (PR #164).

It checks the entire plan before any side effect begins. If any step needs clarification, high-impact approval, a missing permission, an unambiguous adapter choice, or an unsupported capability, the plan is not ready. The gate does not silently run the supported subset.

This is only a preflight result. A separate executor must still:
- obtain the relevant per-action approval/consent;
- create and update the durable execution journal;
- re-observe the target before each UI transition;
- verify every side effect independently;
- stop or reconcile uncertain outcomes rather than blindly retrying.

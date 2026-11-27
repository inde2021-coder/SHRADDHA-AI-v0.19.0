# Trained workflow routing safety

Natural-language matching to a trained workflow is not authorization to perform a destructive or consequential action.

The router refuses automatic dispatch for delete/remove, payment/purchase and publish commands, including common Hindi inflections and Hinglish phrases. It also refuses a single clause that combines multiple distinct action verbs; the caller must split the request into ordered steps and run the normal plan preflight/approval flow.

This guard is intentionally fail-closed. It does not provide a confirmation UI or execute any action. A future approved workflow integration must collect explicit, step-specific consent before invoking a high-impact action.

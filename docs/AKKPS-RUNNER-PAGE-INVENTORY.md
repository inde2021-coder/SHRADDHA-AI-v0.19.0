# AKKPS Runner page inventory

The Runner's Inspect action now captures a structured description of the currently visible AKKPS page:

- visible navigation links from navigation/header/aside regions, restricted to same-origin paths;
- visible form controls without reading their values;
- visible form action labels and form method/path/field counts;
- table headers and row counts;
- current page title and route.

The inventory is stored locally in app-private preferences with bounded size. Query strings and fragments are removed from stored paths, and long numeric/UUID path segments are redacted. The capture does not read or persist cookies, passwords, OTPs, input values, farmer table rows, or record contents. It does not infer modules that are not present in the visible page.

This is a page-observation foundation for Issue #132, not a claim that all private AKKPS modules or save/submit workflows are mapped. The post-login dashboard still needs to be observed from a user-authorized session or user-provided screenshots. CAPTCHA, MFA, role restrictions and site access controls remain untouched.

## Current flow

1. The user opens AKKPS Runner and signs in directly with AKKPS.
2. Inspect reads only the current visible page structure.
3. The page inventory summary is shown in Runner status.
4. A bounded metadata snapshot is retained locally for future module mapping.
5. Form filling and submit behavior remain separate; inventory does not trigger either action.

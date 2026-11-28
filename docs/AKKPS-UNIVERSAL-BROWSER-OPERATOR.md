# AKKPS website access for Shraddha AI

Shraddha AI has two entry points for the user's authorized AKKPS account.

## Android — primary Runner workspace

The Main Brain tool `open_akkps_runner` opens the existing native `AkkpsRunnerActivity` WebView. It can also pass one bounded, explicit user command into the Runner; after the user signs in and the Runner route loads, the command is processed. `inspect_akkps_runner` opens the workspace and captures a safe page inventory after login; `get_akkps_runner_inventory` returns that sanitized inventory to Main Brain. The user signs in directly on the website; credentials and OTPs are never sent to the model.

The sanitized inventory includes module names, safe field labels, form/table counts, table headers and generic actions only; it never includes field values, farmer table rows, cookies or authentication material. The Runner workspace already includes these observed routes:

- Dashboard: `/runner`
- Farmers: `/runner/farmers`
- Plots: `/runner/plots`
- AWD Reports: `/runner/awd-reports`
- Profile: `/runner/profile`
- Data Sync: `/runner/sync`

It includes page inspection, Farmer Card OCR, Farmer/Plot/AWD field mapping, missing-field checks, explicit submit intent, and server-response verification. Create commands can route to the matching Farmer/Plot/AWD form before field mapping; submit still pauses at a separate confirmation dialog. A submit is considered server-confirmed only when the observed same-origin write response is successful and contains a record ID. A toast or button click alone is not enough. Uncertain writes are not automatically retried.

## Windows — visible browser companion

The Windows companion adds an AKKPS Website Operator window. It opens visible Microsoft Edge with a persistent local profile. The user signs in manually, then can open the allow-listed Farmers, Plots, AWD Reports, Profile and Data Sync routes, inspect visible headings/navigation/form labels, preview a field change, and fill one exact unambiguous field after local confirmation.

From the paired Android PC command surface, these commands are supported:

- `Open AKKPS` — open the verified AKKPS portal in Edge.
- `Open AKKPS Runner` — open the Runner route in the same authenticated Edge session.
- `Inspect AKKPS` — return a bounded inventory of visible page structure.
- `Open AKKPS Farmers`, `Open AKKPS Plots`, `Open AKKPS AWD Reports`, `Open AKKPS Profile`, or `Open AKKPS Data Sync` — navigate only to the exact allow-listed Runner module routes.

Every remote command still requires the Windows companion's local approval. Submit is a separate local confirmation. A submit click is reported as unverified until the record is independently checked.

## Main Brain data-entry sequence

1. Call `inspect_akkps_runner`; the user signs in if prompted.
2. Call `get_akkps_runner_inventory` to read the current module's safe field labels and required-field metadata.
3. Ask the user only for missing values; do not infer farmer, plot or report data.
4. Call `open_akkps_runner` with a concise `field=value` command containing only values the user supplied. Create commands route to the matching create form.
5. Review the filled form. If Submit was explicitly requested, the Android Runner shows its confirmation dialog; confirm on the device.
6. Treat the record as saved only after the Runner verifies the same-origin server response and record ID. If the outcome is uncertain, inspect the list manually; never retry automatically.

## Approved origin and privacy

Only HTTPS on `akkps.ngo` and `www.akkps.ngo` is accepted. The similarly spelled `akps.ngo` host is not enabled because it has not been verified as the same service; use the `akkps.ngo` domain shown by the official login page.

The Windows browser profile may contain authenticated cookies and site storage. It stays on the local Windows user profile and must never be committed, uploaded, copied, or shared. Passwords, OTPs and CAPTCHA answers are entered by the user directly into the visible site. Shraddha AI does not read or store them.

No stealth, CAPTCHA bypass, MFA bypass, or access-control bypass is implemented. Delete, bulk update, payment, approval and other irreversible actions are not enabled by this adapter. The private account's real module flows and server-side outcomes still require authorized end-to-end verification by the account owner.

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

Every remote command still requires the Windows companion's local approval. Submit is a separate local confirmation. The Windows operator reports server verification only when it observes an approved-origin POST/PUT/PATCH response with a 2xx status, an explicit success indicator and a record ID. Otherwise the result remains uncertain and the operator warns against retrying until the record list is checked.

## Page-specific AWD, autocomplete and document workflows

The Windows operator now exposes four reviewed page actions in addition to ordinary field entry:

- **Repeated AWD entries:** add one blank row only for the recognized drying-event, re-flooding, fertilizer-application, field-visit, historical-yield or water-reading sections. The operator requires the exact visible `type=button` Add control and verifies that the form gained fields. A separate reviewed action fills exact labels only in the newest row added by that action (not similarly named fields in earlier rows). It does not generate dates, quantities or observations; the user supplies and reviews every value.
- **Autocomplete:** fill a user-provided search phrase into one exact visible field, then select only one exact visible ARIA option. Ambiguous/missing suggestions are rejected. The chosen option must disappear and the field must remain non-empty. Sites that do not expose accessible options remain manual-only.
- **Documents:** the user chooses a local PDF/JPG/JPEG/PNG (maximum 10 MB) and confirms the exact upload field. Shraddha attaches it to the current form and verifies that one file is selected. This is only file selection; it does not claim the portal has uploaded or stored the document. The portal's own upload/submit step remains separate.
- **Map inspection:** the Windows operator reports the detected map engine (when identifiable), visible map surfaces and boundary/drawing controls only. It does not read coordinates or alter map geometry. A page-specific drawing/save adapter must not be enabled until the live authorized Runner map, its controls and its save response are verified.

## Boundary workflow and limits

On Android, the existing Runner workspace supports KML import/export, GeoJSON satellite-candidate import, local encrypted boundary save/reopen, and GPS edge-walking. GPS walk requires a valid Plot ID and precise-location permission, rejects inaccurate/stale/duplicate/jump fixes, checkpoints drafts, and requires geometry validation before local save. The accepted field workflow is satellite candidate first; if no usable candidate is available, walk the field's outer bund/edge without entering the crop. Candidate geometry is reviewed before it replaces the editor points.

**Important:** Android boundary save currently means encrypted local save, not an AKKPS server update. KML/GeoJSON import also does not prove that the AKKPS map overlay has changed. Do not report a boundary as uploaded or saved on the portal until the official Runner map endpoint and a successful server response/record identifier are verified on the authorized account. Existing portal polygons must be preserved; overlap checks and Save→Reopen on the live map remain part of that verification.

## Main Brain data-entry sequence

1. Call `inspect_akkps_runner`; the user signs in if prompted.
2. If the task is a new record, use `open_akkps_runner` with the explicit create intent (for example, `new farmer`, `new plot` or `new AWD report`) to open the matching form.
3. Call `inspect_akkps_runner` and `get_akkps_runner_inventory` again to read that form's safe field labels and required-field metadata.
4. Ask the user only for missing values; do not infer farmer, plot or report data.
5. Call `open_akkps_runner` with a concise `field=value` command containing only values the user supplied. The Runner maps fields and checks required values.
6. Review the filled form. If Submit was explicitly requested, the Android Runner shows its confirmation dialog; confirm on the device.
7. Treat the record as saved only after the Runner verifies the same-origin server response and record ID. If the outcome is uncertain, inspect the list manually; never retry automatically.

## Approved origin and privacy

Only HTTPS on `akkps.ngo` and `www.akkps.ngo` is accepted. The similarly spelled `akps.ngo` host is not enabled because it has not been verified as the same service; use the `akkps.ngo` domain shown by the official login page.

The Windows browser profile may contain authenticated cookies and site storage. It stays on the local Windows user profile and must never be committed, uploaded, copied, or shared. Passwords, OTPs and CAPTCHA answers are entered by the user directly into the visible site. Shraddha AI does not read or store them.

No stealth, CAPTCHA bypass, MFA bypass, or access-control bypass is implemented. Delete, bulk update, payment, approval and other irreversible actions are not enabled by this adapter. The private account's real module flows and server-side outcomes still require authorized end-to-end verification by the account owner.

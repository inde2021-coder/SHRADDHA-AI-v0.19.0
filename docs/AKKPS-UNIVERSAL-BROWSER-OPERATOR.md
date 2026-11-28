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

It includes page inspection, Farmer Card OCR, Farmer/Plot/AWD field mapping, missing-field checks, explicit submit intent, and server-response verification. The Android Runner also accepts voice commands for AWD repeated rows (drying/re-flooding dates, fertilizer applications, field visits, historical yields and water readings), then fills only the newest verified row from explicit user-provided values. Voice autocomplete commands search village, rice variety, linked farmer or linked plot and select only one exact visible suggestion. Document commands open the website's own file picker for the exact supported field; upload remains a separate website action. Plot ID, GPS Walk, KML import/export, local save/reopen, live map inspection, KML handoff and boundary-response verification are also voice-triggerable. Create commands can route to the matching Farmer/Plot/AWD form before field mapping; submit still pauses at a separate confirmation dialog. A submit is considered server-confirmed only when the observed same-origin write response is successful and contains a record ID. A toast or button click alone is not enough. Uncertain writes are not automatically retried.

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

- **Repeated AWD entries:** add one blank row only for recognized drying-event, re-flooding, fertilizer-application (including other-fertilizer rows), field-visit/audit, historical-yield or water-level-reading sections. The operator requires a unique visible section container and an exact visible `type=button` Add control inside that same section. It verifies that the selected section gained fields. A separate reviewed action fills exact labels only in the newest row added by that action (not similarly named fields in earlier rows). It does not generate dates, quantities or observations; the user supplies and reviews every value.
- **Autocomplete:** fill a user-provided search phrase into one exact visible field, then select only one exact visible ARIA option. Ambiguous/missing suggestions are rejected. The chosen option must disappear and the field's resulting value must exactly match the chosen suggestion. Sites that do not expose accessible options remain manual-only.
- **Documents:** the user chooses a local PDF/JPG/JPEG/PNG (maximum 10 MB) and confirms the exact upload field. Shraddha attaches it to the current form and verifies that one file is selected. File selection and upload are separate actions. The explicit Upload/Attach action is allowed only for one visible non-submit `type=button` control and is considered verified only after a same-origin 2xx upload response with an explicit success flag. A timeout or unclear response is uncertain and must not be retried until the portal is checked. The record's final Submit remains separate.
- **Map inspection and KML handoff:** the Windows operator reports the detected map engine (when identifiable), visible map surfaces, boundary/drawing controls and visible file inputs. It can attach a user-reviewed `.kml` file only to one exact visible boundary/KML input on a Runner Plots page; after a separate confirmation it can click one exact visible non-submit boundary Upload/Save button and report success only when a same-origin 2xx response contains explicit success and a record ID. On Android, the Runner can inspect the live Plots page, prepare a validated local boundary as a scoped FileProvider KML, and offer that file only when the approved AKKPS WebView opens a compatible file chooser on a Plots route. The user must confirm the attachment and use the portal's own Upload/Save control. Android can then verify a matching observed boundary/Plot write response with a record ID. Neither attachment path claims that the portal has saved the boundary without that server evidence.

## Boundary workflow and limits

On Android, the existing Runner workspace supports KML import/export, GeoJSON satellite-candidate import, local encrypted boundary save/reopen, and GPS edge-walking. GPS walk requires a valid Plot ID and precise-location permission, rejects inaccurate/stale/duplicate/jump fixes, checkpoints drafts, and requires geometry validation before local save. The accepted field workflow is satellite candidate first; if no usable candidate is available, walk the field's outer bund/edge without entering the crop. Candidate geometry is reviewed before it replaces the editor points.

**Important:** Android boundary Save still means encrypted local save, not an AKKPS server update. KML/GeoJSON import and the KML file-picker handoff do not prove that the AKKPS map overlay has changed. The Android Runner and Windows operator now expose guarded Live Map Draw/Edit entry points: they require the Plots route, one visible map, an exact user-supplied visible Draw/Edit button label and a separate confirmation. They activate only that portal control; the user places or moves vertices manually on the official map. The tools report an active mode only when the page exposes an explicit pressed/selected state. Visible-layer inspection is informational and is not proof of persistence. The authenticated Runner's actual map engine/layer model, exact control labels, overlap behavior, upload endpoint/response and Save→Reopen postcondition still require confirmation on the authorized account. Until that is confirmed, do not report a portal boundary as saved. Existing polygons must be preserved; no automatic polygon replacement or guessed map API is enabled.

## Android voice-command examples

- `set plot id PLOT-101`
- `add drying event`, `add re-flooding date`, `add fertilizer row`, `add field visit`, `add historical yield`, `add water level reading`
- After a row is verified: speak exact `Field Label = value` assignments for that newest row
- `select village Rampur`, `choose rice variety Swarna`, `select farmer Ravi Kumar`, `choose plot PLOT-101`
- `attach field photo`, `attach farmer photo`, `attach Aadhaar`, `attach land deed`, `attach agreement`, `attach signature`
- `inspect live map`, `start map draw`, `start map edit`, `import satellite candidate`, `start gps walk`, `stop gps walk`
- `save local boundary`, `reopen saved boundary`, `import boundary kml`, `export boundary kml`
- `prepare boundary for AKKPS upload`, then open the portal's KML file field and confirm the prepared file; after using the portal's own Upload/Save control, say `verify boundary upload`

A file-picker request or local KML preparation is not a portal upload. Exact labels/options must exist on the current visible page; otherwise the action stops without guessing.

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

## Upload and same-Plot verification hardening (2026-11-28)
- Android exposes **Verify Document Upload** after the user uses the portal's own upload action. It requires a recent same-origin HTTPS write response with an explicit success indicator and a returned document/file/attachment ID. File picker selection is attachment only.
- Windows document upload uses the same conservative outcome: a 2xx response without explicit success and a document/file/attachment ID remains uncertain; do not retry until the portal is checked.
- Boundary upload and Save→Reopen require the active authenticated URL to identify the exact Plot record. A typed Plot ID alone is not sufficient.
- Post-reload map/vector-layer hints are still not proof of exact polygon identity or persistence. The authorized Runner session must visually compare the saved boundary on the same Plot; unknown endpoint or map-engine behavior is not guessed.


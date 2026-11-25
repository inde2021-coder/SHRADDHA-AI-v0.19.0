# Shraddha AI — General Agent Capability Scope

Status: implementation scope and acceptance criteria. This document is not a claim that every capability is already implemented or passed end-to-end tests.

## Product goal

A user should be able to issue one natural-language command. Shraddha should discover the current app/site and available permissions, plan the work, execute supported steps, ask only for genuinely missing or ambiguous information, verify postconditions, recover safely from transient failures, and report exactly what succeeded or remains blocked.

The agent must not claim a task succeeded merely because it clicked a button, opened a page, or dispatched a request.

## 1. AKKPS Runner data intake

Runner scope only: create/enter Farmer, Plot and AWD data; attach requested evidence; submit Runner records. AKKPS staff retain review, validation, approvals, carbon-credit processing, administration and downstream work. Do not expose Admin/Coordinator operations to the Runner agent.

### Identity-card OCR intake
1. User selects or captures an identity document and explicitly authorizes processing.
2. Prefer on-device OCR where the document can be processed accurately. If a configured cloud vision provider is required, obtain separate, clear consent before transmission and identify the provider/data destination.
3. Extract only fields required by the currently visible AKKPS Runner form. Mask or omit identity numbers unless the exact field is required and the user explicitly approves using it.
4. Show each extracted value with source and confidence. Never invent, infer, or silently normalize identity values.
5. Match fields only when there is one unambiguous visible target. Do not overwrite non-empty values without user approval.
6. Inspect required form fields. Ask the user only for required values that are genuinely missing, unreadable, or ambiguous. Accept the answers and revalidate.
7. Present a concise final review. Submit only when the user has explicitly requested submission and all required fields validate.
8. Verify a server-confirmed success state or stable record identifier. If confirmation is unclear, report "submission unverified" and do not retry blindly (to avoid duplicate records).
9. Attach the source image only if AKKPS requires it and the user has approved the upload.

Acceptance: tests cover OCR-to-field mapping, masked identity data, confidence thresholds, duplicate/ambiguous field labels, missing-field questions, validation failures, explicit-submit gating, duplicate-submit prevention and post-submit verification. Live tests must use a test/authorized Runner account and non-production test data where available.

## 2. E-commerce website operations

Supported workflow: search or open a requested shop; find products; display images, variants, prices, delivery/stock details and seller; compare matching products; open product detail; add the selected variant/quantity to cart; and proceed through checkout only as far as the user authorizes.

- Ask for missing variant, size, quantity, address or delivery choice only when it cannot be reliably determined.
- Re-check the final cart, seller, item, quantity, shipping, taxes and total before placing an order.
- Adding to cart may proceed on an unambiguous request. Placing an order, accepting paid subscriptions, or committing payment requires a final explicit confirmation.
- Never store card/UPI PIN/CVV/OTP or bypass CAPTCHA, login, 2FA, anti-bot controls, or a website's terms.
- Use official site APIs/connectors where available; otherwise use permissioned browser/accessibility automation and verify each step.
- If a site blocks automation or needs human verification, pause and explain the exact user action needed.

Acceptance: tests cover search, variant ambiguity, cart reconciliation, stock/price changes, checkout review, payment confirmation gate, login/CAPTCHA pause, and order confirmation verification. Site-specific adapters must be tested against each supported shop; no claim of universal compatibility.

## 3. File operations

- Discover and list files only in user-granted locations.
- Download from a user-specified or trusted page after URL/origin and file-type checks; verify the resulting file exists and its size/hash where practical.
- Upload only user-selected files to the intended destination; show destination and filename before sending.
- Convert supported formats with explicit input/output type, preserve originals by default, and verify output opens.
- Support safe rename/copy/move; destructive delete/overwrite requires confirmation.
- Keep progress, cancellation, retry and failure reporting for large files.
- Do not silently upload local files or scan private folders without a granted picker permission.

Acceptance: format matrix tests, MIME/extension mismatch tests, size limits, duplicate-name handling, interrupted download/upload recovery, checksum/size verification and SAF permission tests.

## 4. Excel and structured data

- Create .xlsx workbooks with named sheets, headers, rows, data types, formulas, filters and sensible column widths.
- Import .xlsx/.csv, preview detected columns, validate values and report malformed rows before writing.
- Append/update only the requested rows; do not silently destroy existing workbook content.
- Export a copy and verify workbook readability and row counts.
- Support Hindi and English text without corrupting characters.

Acceptance: tests cover empty/new workbook, multiple sheets, Hindi strings, dates/numbers, formulas, CSV import/export, malformed input, large files and preservation of original workbook.

## 5. Shared execution engine

All workflows should use a common capability registry and execution journal:
- discover capabilities and permissions before planning;
- explicit plan with dependencies, input requirements and expected postconditions;
- ask one focused question for missing required information;
- use typed actions with least privilege;
- classify reversible, consequential and destructive operations;
- retry only idempotent actions or actions with a reliable duplicate guard;
- verify postconditions independently of action dispatch;
- persist redacted audit events without secrets or unnecessary personal data;
- support pause/resume and cancellation;
- distinguish complete, partially complete, blocked, failed and unverified.

## Existing code discovered (baseline, not proof of full support)

The repository already contains:
- ScreenOcrEngine (on-device Latin/Devanagari screen OCR with sensitive-text suppression);
- FormExtractionClient and FormAutofillActivity (consent-gated image-to-field extraction and user-reviewed per-field fill; this flow intentionally does not submit);
- FileAccessActivity and DownloadUrlPolicy (user-selected file access / URL policy);
- BatchCsvSource (CSV input support);
- an AKKPS Runner WebView and field mapping foundation in the current feature branch.

These components must be integrated and extended; do not duplicate them or describe them as completed universal OCR, document conversion, Excel, shopping or checkout automation.

## Delivery gates

1. Unit tests for pure planners, mappers, policies and parsers.
2. Android compile, unit tests, lint/static checks and emulator instrumentation.
3. App/site adapter tests with deterministic fixtures and failure injection.
4. Live end-to-end test per supported site using authorized test accounts.
5. Physical-device verification for camera, document picker, file permissions, accessibility and foreground-service behavior.
6. Publish a release only after the relevant gates pass. Clearly list capabilities that remain unsupported or need human handoff.

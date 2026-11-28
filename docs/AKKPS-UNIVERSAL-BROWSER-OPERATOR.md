# AKKPS Universal Browser Operator (Windows)

This module adds a visible Playwright Chromium session to the Windows companion so
Shraddha AI can operate the user's authorized AKKPS/TerraMark account through the
same web UI a person uses. It supports the two confirmed hosts:

- https://www.akkps.ngo
- https://www.akps.ngo

The main portal is opened first. After the user signs in manually, **Open Runner**
navigates to the same host's `/runner` route. Whether both hosts share authentication
is not assumed; the user must sign in on whichever host requires it.

## Install and run

The Windows companion dependency set includes Playwright. The Windows package workflow bundles its Python runtime and uses the Microsoft Edge installation already present on Windows. For source development, install `pc_companion/requirements.txt`.

The persistent browser profile is local to the current Windows user. Do not copy,
commit, upload, or share that profile. It may contain authenticated cookies and
site storage. Credentials, passwords, OTPs and CAPTCHA answers are entered by the
user directly in the visible browser; Shraddha AI must not request or retain them.

## Shraddha AI commands\n\nFrom the paired Android PC command surface, the user can say or type:\n\n- `Open AKKPS` — opens the main portal in visible Edge; the user signs in manually.\n- `Open AKKPS Runner` — opens `/runner` on the currently authenticated AKKPS host.\n- `Inspect AKKPS` — returns the visible page title, same-origin navigation, safe field labels, button labels and table headers.\n\nThese commands still require the Windows PC's local approval for each remote action. The Windows companion also exposes the AKKPS Website Operator window for manual inspection and reviewed field entry.\n\n## Safety contract

- Only HTTPS on the four exact AKKPS hostnames above is allowed.
- Browser is headed/visible; no stealth, CAPTCHA bypass, MFA bypass, or access-control bypass.
- Inventory reads visible navigation, field labels, button labels and table headers;
  it does not read form values, farmer row contents, cookies or browser storage.
- Credential/OTP/hidden/file/CAPTCHA fields are excluded from inventory and filling.
- Field fill requires one exact accessible label; fuzzy matches and ambiguous controls fail closed.
- Preview is separate from filling. Submit is a separate call requiring the exact
  human confirmation phrase `SUBMIT AKKPS RECORD`.
- A submit click is **not** proof that the server saved a record. The operator never
  retries a potentially successful submit automatically. Existing Android Runner
  server-response verification and record re-open verification must be used.
- Delete, bulk update, payment, approval and irreversible actions are not automated
  by this first adapter.

This is a first Windows browser adapter, not a declaration that every private AKKPS
module is already mapped. After the user-authorized login, inventory each actual
Runner page and add exact module workflows with tests. Real account end-to-end checks
must be performed by the account owner.

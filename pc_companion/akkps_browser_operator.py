"""User-authorized AKKPS browser operator for the Shraddha AI Windows companion.

The operator uses a visible Microsoft Edge window and a persistent local browser profile.
Credentials and OTPs are entered by the human in the website itself; this module
never accepts, stores, logs, or transmits them. Form submission is a separate,
explicitly confirmed operation.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlparse
import json
import re

ALLOWED_HOSTS = {"www.akkps.ngo", "akkps.ngo"}
DEFAULT_HOME = "https://www.akkps.ngo"
RUNNER_PATH = "/runner"
RUNNER_MODULES = {
    "dashboard": "/runner",
    "farmers": "/runner/farmers",
    "plots": "/runner/plots",
    "awd reports": "/runner/awd-reports",
    "profile": "/runner/profile",
    "data sync": "/runner/sync",
}
SENSITIVE_TYPES = {"password", "hidden", "file"}
UNFILLABLE_TYPES = {"button", "submit", "reset", "image"}
SENSITIVE_LABEL = re.compile(r"\b(password|passcode|otp|one.?time|pin|cvv|cvc|captcha|verification|security.?code|secret.?key|recovery.?phrase)\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड", re.I)


def validate_akkps_url(url: str) -> str:
    """Allow only HTTPS pages on the two explicitly configured AKKPS domains."""
    raw = str(url or "").strip()
    if not raw or len(raw) > 2048 or any(ch.isspace() for ch in raw):
        raise ValueError("AKKPS URL is empty, oversized or contains whitespace.")
    try:
        parsed = urlparse(raw)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("AKKPS URL has an invalid port.") from exc
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS or port not in (None, 443):
        raise ValueError("Only HTTPS pages on the approved AKKPS domains are allowed.")
    if parsed.username or parsed.password:
        raise ValueError("Credentials must not be embedded in a URL.")
    return raw


def _is_explicit_submit_label(label: str) -> bool:
    normalized = " ".join(re.sub(r"[^\w\u0900-\u097f]+", " ", str(label or "").casefold()).split())
    return normalized in {
        "submit", "submit report", "submit plot", "submit registration",
        "submit farmer", "submit farmer registration", "submit awd", "submit awd report",
        "जमा करें", "जमा करो", "सबमिट", "सबमिट करें", "भेजें", "भेजो"
    }


def verified_record_response(method: str, url: str, status: int, payload):
    """Return a record ID only for a successful, approved-origin Runner write response."""
    try:
        validate_akkps_url(url)
    except ValueError:
        return None
    if str(method or "").upper() not in {"POST", "PUT", "PATCH"}:
        return None
    if not isinstance(status, int) or isinstance(status, bool) or not 200 <= status < 300:
        return None
    path = urlparse(url).path.casefold()
    if not re.search(r"(farmer|plot|awd|report|record|boundary)", path):
        return None
    if not isinstance(payload, dict):
        return None
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    record_id = next((payload.get(key) or data.get(key) for key in
                      ("recordId", "farmerId", "plotId", "reportId", "id")
                      if payload.get(key) or data.get(key)), None)
    state = str(payload.get("status") or data.get("status") or "").casefold()
    message = str(payload.get("message") or data.get("message") or "").casefold()
    success = (payload.get("success") is True or payload.get("ok") is True or
               data.get("success") is True or state in {"success", "created", "saved", "ok"} or
               any(term in message for term in
                   ("saved successfully", "submitted successfully", "record created")))
    if not success or record_id is None:
        return None
    value = str(record_id).strip()
    return value[:100] if value and len(value) <= 100 else None


def validate_runner_url(url: str) -> str:
    """Allow only login/root and Runner routes; reject encoded traversal and admin areas."""
    validate_akkps_url(url)
    parsed = urlparse(url)
    raw_path = parsed.path or "/"
    if (len(raw_path) > 1024 or "\\" in raw_path or "//" in raw_path or "%" in raw_path):
        raise ValueError("Unsafe AKKPS route path was rejected.")
    path = raw_path
    if any(segment in {".", ".."} for segment in path.split("/")):
        raise ValueError("AKKPS path traversal was rejected.")
    normalized = path.rstrip("/") or "/"
    segments = [part.casefold() for part in normalized.split("/") if part]
    if any(part in {"admin", "coordinator"} for part in segments):
        raise ValueError("AKKPS Admin and Coordinator routes are not available to Runner automation.")
    allowed_runner = normalized == "/runner" or any(
        normalized == prefix or normalized.startswith(prefix + "/")
        for prefix in (
            "/runner/farmers", "/runner/plots", "/runner/awd-reports",
            "/runner/profile", "/runner/sync"
        )
    ) or normalized in {"/plots/new", "/awd-reports/new"}
    allowed_login = normalized in {"/", "/login", "/auth/login", "/signin", "/sign-in", "/auth/signin", "/otp", "/verify", "/two-factor"} or normalized.startswith("/auth/") or normalized.startswith("/login/")
    if not allowed_login and not allowed_runner:
        raise ValueError("Only AKKPS login/verification and allow-listed Runner modules are allowed.")
    return url


@dataclass(frozen=True)
class FieldInfo:
    label: str
    name: str
    input_type: str
    required: bool
    disabled: bool
    read_only: bool = False
    multiple: bool = False
    options: tuple[str, ...] = ()


@dataclass(frozen=True)
class PageInventory:
    url: str
    title: str
    links: tuple[str, ...]
    fields: tuple[FieldInfo, ...]
    buttons: tuple[str, ...]
    table_headers: tuple[tuple[str, ...], ...]
    sections: tuple[str, ...] = ()
    forms: tuple[tuple[str, ...], ...] = ()


def _safe_action_label(value: str) -> str | None:
    text = " ".join(str(value or "").casefold().split())
    patterns = (
        (r"^(create|add|new|register|बनाएं|बनाओ|नया|नई|जोड़ें|जोड़ो)(?:\s|$)", "Create"),
        (r"^(edit|update|संपादित|बदलें|बदलना)(?:\s|$)", "Edit"),
        (r"^(save|submit|सहेजें|जमा|सबमिट)(?:\s|$)", "Submit"),
        (r"^(search|find|filter|खोज|ढूंढ|ढूँढ)(?:\s|$)", "Search"),
        (r"^(view|open|details|देखें|खोलें)(?:\s|$)", "View"),
        (r"^(upload|attach|अपलोड|संलग्न)(?:\s|$)", "Upload"),
        (r"^(delete|remove|हटाएं|मिटाएं|डिलीट)(?:\s|$)", "Delete"),
        (r"^(sync|सिंक)(?:\s|$)", "Sync"),
        (r"^(cancel|back|रद्द|वापस)(?:\s|$)", "Cancel"),
    )
    for pattern, safe in patterns:
        if re.search(pattern, text, re.I):
            return safe
    return None


def _safe_runner_location(url: str) -> tuple[str, str]:
    parsed = urlparse(url)
    path = parsed.path.rstrip("/") or "/"
    for label, base in sorted(RUNNER_MODULES.items(), key=lambda item: len(item[1]), reverse=True):
        if path == base or path.startswith(base + "/"):
            safe_path = path if path == base or path == base + "/new" else base
            return f"https://{parsed.hostname}{safe_path}", label.upper()
    if path == "/plots/new":
        return f"https://{parsed.hostname}/plots/new", "PLOTS"
    if path == "/awd-reports/new":
        return f"https://{parsed.hostname}/awd-reports/new", "AWD REPORTS"
    return f"https://{parsed.hostname}{path}", "AKKPS LOGIN"


class AkkpsBrowserOperator:
    """Visible, human-in-the-loop browser session for AKKPS/TerraMark."""

    def __init__(self, profile_dir: str | Path):
        self.profile_dir = Path(profile_dir).expanduser().resolve()
        self._playwright = None
        self._context = None
        self._page = None
        self._last_repeater_snapshot = None

    @property
    def page(self):
        if self._page is None:
            raise RuntimeError("Browser is not open. Start it and sign in manually first.")
        return self._page

    def start(self, start_url: str = DEFAULT_HOME):
        validate_runner_url(start_url)
        if self._page is not None:
            return self.inventory()
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise RuntimeError(
                "Browser automation is optional. Install Playwright with `python -m pip install playwright==1.55.0` and install Microsoft Edge."
            ) from exc
        self.profile_dir.mkdir(parents=True, exist_ok=True)
        try:
            self._playwright = sync_playwright().start()
            self._context = self._playwright.chromium.launch_persistent_context(
                user_data_dir=str(self.profile_dir),
                headless=False,
                channel="msedge",
                accept_downloads=True,
                viewport={"width": 1440, "height": 900},
            )
            self._page = self._context.pages[0] if self._context.pages else self._context.new_page()
            self._page.goto(start_url, wait_until="domcontentloaded", timeout=45000)
            return self.inventory()
        except Exception:
            self.close()
            raise

    def close(self):
        try:
            if self._context:
                self._context.close()
        finally:
            try:
                if self._playwright:
                    self._playwright.stop()
            finally:
                self._context = self._playwright = self._page = None

    def navigate(self, url: str):
        validate_runner_url(url)
        self.page.goto(url, wait_until="domcontentloaded", timeout=45000)
        return self.inventory()

    def open_runner(self):
        current = urlparse(self.page.url)
        if current.hostname not in ALLOWED_HOSTS:
            raise RuntimeError("Sign in on the approved AKKPS portal before opening Runner.")
        target = f"https://{current.hostname}{RUNNER_PATH}"
        return self.navigate(target)

    def open_module(self, module: str):
        """Navigate only to an observed, allow-listed Runner module route."""
        key = " ".join(str(module or "").casefold().split())
        route = RUNNER_MODULES.get(key)
        if route is None:
            raise ValueError("Unknown AKKPS Runner module.")
        current = urlparse(self.page.url)
        if current.hostname not in ALLOWED_HOSTS:
            raise RuntimeError("Open the AKKPS portal and sign in before choosing a Runner module.")
        return self.navigate(f"https://{current.hostname}{route}")

    def inventory(self) -> PageInventory:
        """Read visible page structure only; never collect input values or row contents."""
        data = self.page.evaluate("""() => {
          const visible = e => !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length));
          const clean = s => (s || '').replace(/\s+/g, ' ').trim().slice(0, 160);
          const labelFor = e => {
            const labels = [...(e.labels || [])].map(x => clean(x.innerText)).filter(Boolean);
            const labelledBy = (e.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean)
              .map(id => clean(document.getElementById(id)?.innerText)).filter(Boolean);
            const fieldset = e.closest('fieldset');
            const legend = fieldset?.querySelector('legend');
            return clean(
              labels[0] || labelledBy[0] || e.getAttribute('aria-label') ||
              e.closest('label')?.innerText || legend?.innerText ||
              e.getAttribute('placeholder') || e.name || e.id
            );
          };
          const visibleForms = [...document.querySelectorAll('form')].filter(visible);
          const fieldScope = visibleForms.length === 1 ? visibleForms[0] : document;
          const fields = [...fieldScope.querySelectorAll('input,select,textarea')]
            .filter(visible).map(e => ({
              label: labelFor(e), name: clean(e.name || e.id),
              type: clean(e.tagName.toLowerCase() === "select" ? "select" : (e.type || e.tagName.toLowerCase())).toLowerCase(),
              required: !!e.required, disabled: !!e.disabled, readOnly: !!e.readOnly, multiple: !!e.multiple,
              options: e.tagName === 'SELECT' ? [...e.options].map(o => clean(o.label || o.textContent)).filter(Boolean).slice(0, 80) : []
            }));
          const links = [...document.querySelectorAll('nav a, header a, aside a, [role=navigation] a')]
            .filter(visible).map(a => a.href).filter(h => h.startsWith(location.origin)).slice(0, 100);
          const buttons = [...document.querySelectorAll('button,input[type=submit],[role=button]')]
            .filter(visible).map(e => clean(e.innerText || e.value || e.getAttribute('aria-label'))).filter(Boolean).slice(0, 100);
          const tables = [...document.querySelectorAll('table')].filter(visible).slice(0, 30)
            .map(t => [...t.querySelectorAll('thead th')].map(th => clean(th.innerText)).filter(Boolean));
          const sections = [...document.querySelectorAll('h1,h2,h3,[role=heading],nav a,aside a')]
            .filter(visible).map(e => clean(e.innerText)).filter(Boolean).slice(0, 120);
          const forms = [...document.querySelectorAll('form')].filter(visible).slice(0, 30)
            .map((f, index) => ({
              index,
              fieldCount: [...f.querySelectorAll('input,select,textarea')].filter(visible).length,
              submitLabels: [...f.querySelectorAll('button[type=submit],input[type=submit]')]
                .filter(visible).map(e => clean(e.innerText || e.value || e.getAttribute('aria-label'))).filter(Boolean)
            }));
          return {url: location.href, title: document.title, links, fields, buttons, tables, sections, forms};
        }""")
        validate_runner_url(data["url"])
        safe_fields = []
        for item in data["fields"]:
            kind = (item.get("type") or "").lower()
            label = item.get("label") or ""
            # Never expose credential, OTP, hidden, file or CAPTCHA controls.
            if kind in SENSITIVE_TYPES or kind in UNFILLABLE_TYPES or SENSITIVE_LABEL.search(label):
                continue
            safe_fields.append(FieldInfo(
                label=label, name=item.get("name", ""), input_type=kind,
                required=bool(item.get("required")), disabled=bool(item.get("disabled")),
                read_only=bool(item.get("readOnly")), multiple=bool(item.get("multiple")),
                options=tuple(" ".join(str(option).split())[:120] for option in item.get("options", ())[:80]),
            ))
        safe_url, safe_title = _safe_runner_location(data["url"])
        safe_links = []
        for href in data["links"]:
            try:
                parsed_link = urlparse(href)
                validate_runner_url(href)
                if parsed_link.path.rstrip("/") in set(RUNNER_MODULES.values()):
                    safe_links.append(f"https://{parsed_link.hostname}{parsed_link.path.rstrip('/')}")
            except ValueError:
                continue
        safe_buttons = tuple(dict.fromkeys(
            label for label in (_safe_action_label(x) for x in data["buttons"]) if label
        ))
        safe_sections = tuple(dict.fromkeys(
            label for label in (
                _safe_action_label(x) or (
                    " ".join(str(x).casefold().split()).upper()
                    if " ".join(str(x).casefold().split()) in RUNNER_MODULES else None
                ) for x in data.get("sections", ())
            ) if label
        ))
        safe_forms = tuple(
            (f"Form {max(0, min(int(form.get('index', index)), 29)) + 1} "
             f"({max(0, min(int(form.get('fieldCount', 0)), 200))} fields)",
             *tuple(_safe_action_label(x) for x in form.get("submitLabels", ())
                    if _safe_action_label(x)))
            for index, form in enumerate(data.get("forms", ())[:20])
            if isinstance(form, dict)
        )
        return PageInventory(
            url=safe_url, title=safe_title,
            links=tuple(safe_links), fields=tuple(safe_fields),
            buttons=safe_buttons, table_headers=tuple(tuple(x) for x in data["tables"]),
            sections=safe_sections, forms=safe_forms,
        )

    def fill_exact_label(self, label: str, value: str):
        """Fill one unique, visible, enabled non-sensitive field by exact accessible label."""
        wanted = " ".join(str(label).casefold().split())
        if not wanted or not isinstance(value, str):
            raise ValueError("A field label and text value are required.")
        visible_forms = self.page.locator("form:visible")
        if visible_forms.count() != 1:
            raise RuntimeError(f"Expected exactly one visible form; found {visible_forms.count()}.")
        form = visible_forms.first
        matches = form.get_by_label(label, exact=True)
        count = matches.count()
        if count == 0:
            # Inventory labels may fall back to placeholder or the DOM name/id
            # when the site omits a real <label for=...> association.
            matches = form.get_by_placeholder(label, exact=True)
            count = matches.count()
        if count == 0:
            all_fields = form.locator("input,select,textarea")
            indices = all_fields.evaluate_all(
                "(elements, exact) => elements.map((e, i) => ({i, match: e.name === exact || e.id === exact || e.placeholder === exact}))"
                ".filter(x => x.match).map(x => x.i)",
                label,
            )
            count = len(indices)
            if count == 1:
                matches = all_fields.nth(indices[0])
        if count != 1:
            raise RuntimeError(f"Expected exactly one field labelled {label!r}; found {count}.")
        field = matches.first
        if not field.is_visible() or not field.is_enabled() or field.get_attribute("readonly") is not None:
            raise RuntimeError("The target field is hidden, disabled or read-only.")
        field_type = (field.get_attribute("type") or "").lower()
        if field_type in SENSITIVE_TYPES or field_type in UNFILLABLE_TYPES or SENSITIVE_LABEL.search(label):
            raise RuntimeError("Credential, OTP, hidden and file fields cannot be auto-filled.")
        tag = field.evaluate("(e) => e.tagName.toLowerCase()")
        if tag == "select":
            if field.get_attribute("multiple") is not None:
                raise RuntimeError("Multi-select fields require a dedicated reviewed action.")
            options = [" ".join(text.split()) for text in field.locator("option").all_text_contents()]
            if value not in options:
                raise RuntimeError("Dropdown value must exactly match a visible option.")
            field.select_option(label=value)
        elif field_type in {"checkbox", "radio"}:
            raise RuntimeError("Checkbox/radio choices require a dedicated reviewed action.")
        else:
            field.fill(value)
        return {"field": label, "filled": True, "submitted": False}

    def preview(self, values: dict[str, str]):
        """Validate proposed fields and return a preview without changing the website."""
        inventory = self.inventory()
        grouped = {}
        for field in inventory.fields:
            grouped.setdefault(" ".join(field.label.casefold().split()), []).append(field)
        preview = []
        for label, value in values.items():
            matches = grouped.get(" ".join(str(label).casefold().split()), [])
            if len(matches) != 1:
                raise RuntimeError(f"Field {label!r} is missing or ambiguous in the current page inventory.")
            field = matches[0]
            if field.disabled or field.read_only:
                raise RuntimeError(f"Field {label!r} is disabled or read-only.")
            if field.multiple or field.input_type in SENSITIVE_TYPES or field.input_type in UNFILLABLE_TYPES or field.input_type in {"checkbox", "radio"}:
                raise RuntimeError(f"Field {label!r} is not an auto-fillable text/select field.")
            if field.input_type == "select" and str(value) not in field.options:
                raise RuntimeError(f"Value {value!r} is not an exact visible option for {label!r}.")
            preview.append({"field": field.label, "value": str(value), "required": field.required,
                            "available_options": list(field.options) if field.input_type == "select" else []})
        return {"page": inventory.title, "url": inventory.url, "changes": preview,
                "requires_user_confirmation": True, "submitted": False}

    def fill_reviewed_fields(self, values: dict[str, str]):
        """Pre-validate every exact label, then fill a reviewed batch without submitting."""
        if not isinstance(values, dict) or not values or len(values) > 100:
            raise ValueError("Provide 1–100 field-label/value pairs.")
        preview = self.preview(values)
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("The page form changed after preview; no fields were filled.")
        form = forms.first
        planned = []
        for label, value in values.items():
            matches = form.get_by_label(label, exact=True)
            if matches.count() == 0:
                matches = form.get_by_placeholder(label, exact=True)
            if matches.count() == 0:
                all_fields = form.locator("input,select,textarea")
                indexes = all_fields.evaluate_all(
                    "(elements, exact) => elements.map((e, i) => ({i, match: e.name === exact || e.id === exact || e.placeholder === exact})).filter(x => x.match).map(x => x.i)",
                    label,
                )
                if len(indexes) == 1:
                    matches = all_fields.nth(indexes[0])
            if matches.count() != 1:
                raise RuntimeError(f"Field {label!r} changed or became ambiguous; no fields were filled.")
            field = matches.first
            field_type = (field.get_attribute("type") or "").lower()
            if not field.is_visible() or not field.is_enabled() or field.get_attribute("readonly") is not None:
                raise RuntimeError(f"Field {label!r} is no longer editable; no fields were filled.")
            if field_type in SENSITIVE_TYPES | UNFILLABLE_TYPES or SENSITIVE_LABEL.search(label):
                raise RuntimeError(f"Field {label!r} is sensitive or not fillable.")
            if field_type in {"checkbox", "radio"} or field.get_attribute("multiple") is not None:
                raise RuntimeError(f"Field {label!r} requires a dedicated choice handler.")
            planned.append((label, str(value), field))
        # Recheck the page URL and form identity immediately before any write.
        validate_runner_url(self.page.url)
        if self.page.locator("form:visible").count() != 1:
            raise RuntimeError("The form changed before field entry; no fields were filled.")
        filled = []
        for label, value, field in planned:
            if field.evaluate("(e) => e.tagName.toLowerCase()") == "select":
                field.select_option(label=value)
            else:
                field.fill(value)
            filled.append(label)
        return {"page": preview["page"], "filled_fields": filled,
                "submitted": False, "requires_separate_submit_confirmation": True}

    def submit_after_confirmation(self, confirmation: str):
        """Submit only after exact confirmation; verify a record ID or leave outcome uncertain."""
        if confirmation != "SUBMIT AKKPS RECORD":
            raise PermissionError("Explicit confirmation phrase is required before submission.")
        page = self.page
        before_url = page.url
        validate_runner_url(before_url)
        forms = page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError(f"Expected exactly one visible form; found {forms.count()}. Refusing submission.")
        form = forms.first
        action_url = form.get_attribute("action") or before_url
        from urllib.parse import urljoin
        action_url = urljoin(before_url, action_url)
        validate_akkps_url(action_url)
        if urlparse(action_url).netloc.casefold() != urlparse(before_url).netloc.casefold():
            raise RuntimeError("Cross-origin form submission is blocked.")
        action_path = urlparse(action_url).path.casefold()
        if re.search(r"/(admin|coordinator)(/|$)", action_path):
            raise RuntimeError("Admin/Coordinator submissions are outside the Runner scope.")
        candidates = form.locator('button, input[type="submit"], [role="button"]')
        matched = []
        for index in range(candidates.count()):
            candidate = candidates.nth(index)
            if not candidate.is_visible() or not candidate.is_enabled():
                continue
            is_input = candidate.evaluate("(e) => e.tagName === 'INPUT'")
            label = (candidate.get_attribute("aria-label") or candidate.get_attribute("title") or
                     (candidate.input_value() if is_input else candidate.inner_text()))
            if _is_explicit_submit_label(label):
                matched.append(candidate)
        if len(matched) != 1:
            raise RuntimeError(f"Expected one explicit final Submit control; found {len(matched)}. Refusing submission.")
        button = matched[0]
        response = None
        clicked = False
        try:
            with page.expect_response(
                lambda item: (
                    item.request.method.upper() in {"POST", "PUT", "PATCH"} and
                    urlparse(item.url).hostname in ALLOWED_HOSTS and
                    bool(re.search(r"(farmer|plot|awd|report|record|boundary)",
                                   urlparse(item.url).path, re.I))
                ),
                timeout=15000
            ) as response_info:
                button.click()
                clicked = True
            response = response_info.value
        except Exception:
            # The click may have reached the server even if the browser did not
            # expose a matching response. Never retry a potentially accepted write.
            return {"clicked": clicked, "click_attempted": True, "before_url": before_url,
                    "after_url": page.url if not page.is_closed() else before_url,
                    "server_record_verified": False, "record_id": None,
                    "outcome": "uncertain", "automatic_retry": False}
        record_id = None
        try:
            payload = response.json()
            record_id = verified_record_response(
                response.request.method, response.url, response.status, payload
            )
        except Exception:
            record_id = None
        return {
            "clicked": True, "before_url": before_url,
            "after_url": page.url if not page.is_closed() else before_url,
            "server_record_verified": bool(record_id), "record_id": record_id,
            "outcome": "verified" if record_id else "uncertain",
            "automatic_retry": False
        }


    def _awd_section(self, section_label: str):
        """Resolve one exact AWD section container; never fall back to the whole form."""
        heading = self.page.get_by_text(section_label, exact=True)
        if heading.count() != 1 or not heading.first.is_visible():
            raise RuntimeError("The repeated-entry section heading is missing or ambiguous.")
        section = heading.first.locator(
            "xpath=ancestor::*[self::fieldset or self::section or @data-section or @role='group'][1]"
        )
        if section.count() != 1 or not section.first.is_visible():
            raise RuntimeError("The AWD section has no unique accessible container; refusing an unscoped Add action.")
        return section.first

    def add_repeated_entry(self, section_label: str, add_button_label: str):
        """Add one blank row only inside the exact recognized AWD section."""
        section_label = " ".join(str(section_label or "").split())
        add_button_label = " ".join(str(add_button_label or "").split())
        allowed_sections = {
            "drying events", "drying event", "drying event dates", "drying dates",
            "re-flooding events", "re-flooding dates", "reflooding dates",
            "fertilizer applications", "fertilizer application dates", "other fertilizer rows",
            "field visits", "field visit audit logs", "audit logs",
            "historical yields", "historical yield rows",
            "water level readings", "water level at each visit", "visit readings"
        }
        normalized = section_label.casefold()
        section_aliases = {
            "सूखने की तारीख": "drying event dates", "सूखने की तारीखें": "drying event dates",
            "पुनः बाढ़ की तारीख": "reflooding dates", "पुनः बाढ़ की तारीखें": "reflooding dates",
            "खाद डालने की तारीख": "fertilizer application dates",
            "खाद डालने की तारीखें": "fertilizer application dates",
            "अन्य खाद": "other fertilizer rows", "अन्य उर्वरक": "other fertilizer rows",
            "खेत का दौरा": "field visits", "खेत के दौरे": "field visits",
            "ऐतिहासिक उपज": "historical yield rows",
            "जल स्तर रीडिंग": "water level readings",
            "हर दौरे का जल स्तर": "water level at each visit",
            "दौरे का रिकॉर्ड": "audit logs"
        }
        normalized = section_aliases.get(normalized, normalized)
        if normalized not in allowed_sections:
            raise ValueError("Only recognized AWD repeated-entry sections are supported.")
        if not add_button_label or len(add_button_label) > 80 or SENSITIVE_LABEL.search(add_button_label):
            raise ValueError("A safe, exact visible Add-row label is required.")
        validate_runner_url(self.page.url)
        if not urlparse(self.page.url).path.casefold().startswith("/runner/awd-reports"):
            raise RuntimeError("Repeated AWD rows are available only on an AWD Reports page.")
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible AWD form.")
        section = self._awd_section(section_label)
        add = section.get_by_role("button", name=add_button_label, exact=True)
        if add.count() != 1 or not add.first.is_visible() or not add.first.is_enabled():
            raise RuntimeError("The exact Add-row control is missing, ambiguous or disabled in this section.")
        if (add.first.get_attribute("type") or "").casefold() != "button":
            raise RuntimeError("Add-row control is not explicitly type=button; refusing a possible form submit.")
        fields = section.locator("input,select,textarea")
        before = fields.count()
        baseline_signature = fields.evaluate_all(
            "(els) => els.map(e => [e.tagName, e.type || '', e.name || '', e.id || '', e.placeholder || ''])"
        )
        add.first.click()
        self.page.wait_for_timeout(250)
        after = section.locator("input,select,textarea").count()
        if after <= before:
            raise RuntimeError("No new fields appeared inside the selected AWD section; row addition was not verified.")
        self._last_repeater_snapshot = {
            "section": section_label, "url": self.page.url,
            "baseline_signature": baseline_signature,
            "baseline_field_count": before
        }
        return {
            "section": section_label, "fields_before": before, "fields_after": after,
            "row_added": True, "submitted": False,
            "next_step": "Review and fill the newest row with explicit user-provided values."
        }

    def _plan_latest_repeated_entry(self, values: dict[str, str]):
        snapshot = self._last_repeater_snapshot
        if not snapshot or snapshot.get("url") != self.page.url:
            raise RuntimeError("Add a new AWD row on this page before filling a repeated entry.")
        if not urlparse(self.page.url).path.casefold().startswith("/runner/awd-reports"):
            raise RuntimeError("Repeated AWD entries are available only on AWD Reports pages.")
        if not isinstance(values, dict) or not values or len(values) > 30:
            raise ValueError("Provide 1–30 exact field-label/value pairs for the newest row.")
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible AWD form.")
        section = self._awd_section(snapshot["section"])
        fields = section.locator("input,select,textarea")
        baseline = int(snapshot["baseline_field_count"])
        if fields.count() <= baseline:
            raise RuntimeError("The new AWD row is no longer present in the selected section.")
        current_signature = fields.evaluate_all(
            "(els) => els.map(e => [e.tagName, e.type || '', e.name || '', e.id || '', e.placeholder || ''])"
        )
        if current_signature[:baseline] != snapshot.get("baseline_signature"):
            raise RuntimeError("The AWD section changed after the row was added; refusing to fill a different row.")
        planned = []
        for label, value in values.items():
            label = " ".join(str(label or "").split())
            if not label or not isinstance(value, str) or len(value) > 2000 or SENSITIVE_LABEL.search(label):
                raise ValueError("Every repeated-row field needs a safe exact label and text value.")
            matches = section.get_by_label(label, exact=True)
            if matches.count() == 0:
                matches = section.get_by_placeholder(label, exact=True)
            candidates = []
            for index in range(matches.count()):
                candidate = matches.nth(index)
                position = fields.evaluate_all(
                    "(els, target) => els.indexOf(target)",
                    candidate.element_handle()
                )
                if position >= baseline:
                    candidates.append(candidate)
            if len(candidates) != 1:
                raise RuntimeError(f"New-row field {label!r} is missing or ambiguous.")
            field = candidates[0]
            kind = (field.get_attribute("type") or "").casefold()
            tag = field.evaluate("(e) => e.tagName.toLowerCase()")
            if not field.is_visible() or not field.is_enabled() or field.get_attribute("readonly") is not None:
                raise RuntimeError(f"New-row field {label!r} is not editable.")
            if kind in SENSITIVE_TYPES | UNFILLABLE_TYPES | {"checkbox", "radio"} or field.get_attribute("multiple") is not None:
                raise RuntimeError(f"New-row field {label!r} needs a separate safe handler.")
            if tag == "select":
                options = [" ".join(x.split()) for x in field.locator("option").all_text_contents()]
                if value not in options:
                    raise RuntimeError(f"{value!r} is not an exact option for {label!r}.")
            planned.append((label, value, field))
        return {
            "section": snapshot["section"],
            "changes": [{"field": label, "value": value} for label, value, _ in planned],
            "submitted": False, "requires_confirmation": True
        }, planned

    def preview_latest_repeated_entry(self, values: dict[str, str]):
        preview, _ = self._plan_latest_repeated_entry(values)
        return preview

    def fill_latest_repeated_entry(self, values: dict[str, str]):
        """Fill only fields added after the last confirmed AWD row-add action."""
        preview, planned = self._plan_latest_repeated_entry(values)
        validate_runner_url(self.page.url)
        filled = []
        for label, value, field in planned:
            if field.evaluate("(e) => e.tagName.toLowerCase()") == "select":
                field.select_option(label=value)
            else:
                field.fill(value)
            filled.append(label)
        self._last_repeater_snapshot = None
        return {
            "section": preview["section"], "filled_fields": filled,
            "submitted": False, "requires_separate_submit_confirmation": True
        }

    def choose_autocomplete_option(self, field_label: str, query: str, exact_option: str):
        """Choose one exact visible autocomplete suggestion after a user-reviewed query."""
        field_label = " ".join(str(field_label or "").split())
        query = str(query or "").strip()
        exact_option = " ".join(str(exact_option or "").split())
        if not field_label or not query or not exact_option or len(query) > 160 or len(exact_option) > 160:
            raise ValueError("Field label, search text and exact suggestion are required.")
        if SENSITIVE_LABEL.search(field_label):
            raise ValueError("Sensitive fields cannot use autocomplete automation.")
        validate_runner_url(self.page.url)
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible form.")
        form = forms.first
        field = form.get_by_label(field_label, exact=True)
        if field.count() == 0:
            field = form.get_by_placeholder(field_label, exact=True)
        if field.count() != 1 or not field.first.is_visible() or not field.first.is_enabled():
            raise RuntimeError("Autocomplete field is missing, ambiguous or disabled.")
        field.first.fill(query)
        suggestions = self.page.get_by_role("option", name=exact_option, exact=True)
        count = suggestions.count()
        if count != 1 or not suggestions.first.is_visible() or not suggestions.first.is_enabled():
            raise RuntimeError("The exact autocomplete suggestion is not uniquely visible; nothing was selected.")
        suggestions.first.click()
        self.page.wait_for_timeout(150)
        still_visible = self.page.get_by_role("option", name=exact_option, exact=True).count()
        selected_value = field.first.evaluate("(e) => String(e.value || '').trim()")
        if still_visible or selected_value.casefold() != exact_option.casefold():
            raise RuntimeError("Autocomplete selection could not be verified from the selected field value; review it manually.")
        return {"field": field_label, "selected_option": exact_option,
                "selection_verified": True, "submitted": False}

    def attach_document(self, field_label: str, file_path: str | Path):
        """Attach a user-selected document to one exact file input; does not upload/submit."""
        from pathlib import Path
        path = Path(file_path).expanduser().resolve()
        allowed = {".pdf", ".jpg", ".jpeg", ".png"}
        if not path.is_file() or path.suffix.casefold() not in allowed:
            raise ValueError("Choose an existing PDF, JPG or PNG document.")
        if path.stat().st_size <= 0 or path.stat().st_size > 10 * 1024 * 1024:
            raise ValueError("Document must be non-empty and no larger than 10 MB.")
        with path.open("rb") as source:
            signature = source.read(16)
        valid_signature = (
            (path.suffix.casefold() == ".pdf" and signature.startswith(b"%PDF-")) or
            (path.suffix.casefold() in {".jpg", ".jpeg"} and signature.startswith(b"\xff\xd8\xff")) or
            (path.suffix.casefold() == ".png" and signature.startswith(b"\x89PNG\r\n\x1a\n"))
        )
        if not valid_signature:
            raise ValueError("Document contents do not match the selected PDF/JPEG/PNG extension.")
        label = " ".join(str(field_label or "").split())
        if not label or SENSITIVE_LABEL.search(label):
            raise ValueError("A safe exact document field label is required.")
        validate_runner_url(self.page.url)
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible form.")
        form = forms.first
        candidates = form.locator('input[type="file"]')
        matches = []
        for index in range(candidates.count()):
            item = candidates.nth(index)
            accessible = item.get_attribute("aria-label") or item.get_attribute("name") or item.get_attribute("id") or ""
            labels = item.evaluate("(e) => [...(e.labels || [])].map(x => (x.innerText || '').trim())")
            if label == accessible or label in labels:
                matches.append(item)
        if len(matches) != 1:
            raise RuntimeError("Exact document upload field is missing or ambiguous.")
        target = matches[0]
        accept = (target.get_attribute("accept") or "").casefold()
        extension_mime = {".pdf": "application/pdf", ".jpg": "image/jpeg",
                          ".jpeg": "image/jpeg", ".png": "image/png"}
        accepted = [token.strip() for token in accept.split(",") if token.strip()]
        suffix = path.suffix.casefold()
        mime = extension_mime[suffix]
        if accepted and not any(
            token in {suffix, mime} or (token == "image/*" and mime.startswith("image/"))
            for token in accepted
        ):
            raise ValueError("Selected document type is not allowed by this upload field.")
        target.set_input_files(str(path))
        count = target.evaluate("(e) => e.files ? e.files.length : 0")
        if count != 1:
            raise RuntimeError("Document selection could not be verified.")
        return {"field": label, "file_attached": True, "file_count": count,
                "upload_submitted": False, "record_submitted": False}

    def upload_attached_document_after_confirmation(self, upload_button_label: str):
        """Click one explicit non-submit Upload control and verify its same-origin response."""
        label = " ".join(str(upload_button_label or "").split())
        if not label or len(label) > 80 or SENSITIVE_LABEL.search(label) or _safe_action_label(label) != "Upload":
            raise ValueError("An exact visible Upload/Attach button label is required.")
        validate_runner_url(self.page.url)
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible document form.")
        files = forms.first.locator('input[type="file"]')
        selected = 0
        for index in range(files.count()):
            selected += int(files.nth(index).evaluate("(e) => e.files ? e.files.length : 0"))
        if selected < 1:
            raise RuntimeError("Choose and review a document before uploading.")
        button = forms.first.get_by_role("button", name=label, exact=True)
        if button.count() != 1 or not button.first.is_visible() or not button.first.is_enabled():
            raise RuntimeError("Exact Upload control is missing, ambiguous or disabled.")
        if (button.first.get_attribute("type") or "").casefold() != "button":
            raise RuntimeError("Upload control may submit the whole record; refusing this action.")
        origin = urlparse(self.page.url).netloc.casefold()
        response = None
        clicked = False
        try:
            with self.page.expect_response(
                lambda item: (
                    item.request.method.upper() in {"POST", "PUT"} and
                    urlparse(item.url).netloc.casefold() == origin and
                    bool(re.search(r"(upload|document|attachment|file)", urlparse(item.url).path, re.I))
                ), timeout=20000
            ) as response_info:
                button.first.click()
                clicked = True
            response = response_info.value
        except Exception:
            return {"clicked": clicked, "upload_verified": False, "outcome": "uncertain",
                    "automatic_retry": False, "record_submitted": False}
        explicit_success = False
        try:
            payload = response.json()
            if isinstance(payload, dict):
                data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
                explicit_success = (payload.get("success") is True or payload.get("ok") is True or
                                    data.get("success") is True or
                                    str(payload.get("status") or data.get("status") or "").casefold()
                                    in {"success", "uploaded", "created", "ok"})
        except Exception:
            explicit_success = False
        verified = 200 <= response.status < 300 and explicit_success
        return {"clicked": True, "http_status": response.status,
                "upload_verified": verified, "outcome": "verified" if verified else "uncertain",
                "automatic_retry": False, "record_submitted": False}

    def attach_boundary_kml(self, field_label: str, file_path: str | Path):
        """Attach a reviewed KML to the exact visible Plot boundary input; never saves the record."""
        from pathlib import Path
        label = " ".join(str(field_label or "").split())
        path = Path(file_path).expanduser().resolve()
        if not label or not re.search(r"(boundary|kml|सीमा|नक्शा|मानचित्र)", label, re.I):
            raise ValueError("Use the exact visible Plot boundary/KML upload field label.")
        if path.suffix.casefold() != ".kml" or not path.is_file():
            raise ValueError("Choose an existing .kml boundary file.")
        if path.stat().st_size <= 0 or path.stat().st_size > 10 * 1024 * 1024:
            raise ValueError("KML must be non-empty and no larger than 10 MB.")
        import xml.etree.ElementTree as ET
        try:
            root = ET.fromstring(path.read_bytes())
        except (ET.ParseError, OSError) as exc:
            raise ValueError("Boundary file is not valid KML/XML.") from exc
        local = lambda node: node.tag.rsplit("}", 1)[-1].casefold()
        polygons = [node for node in root.iter() if local(node) == "polygon"]
        if len(polygons) != 1 or any(local(node) == "innerboundaryis" for node in root.iter()):
            raise ValueError("KML must contain exactly one Polygon outer ring and no interior holes.")
        coordinate_nodes = [node for node in polygons[0].iter() if local(node) == "coordinates"]
        if len(coordinate_nodes) != 1 or not (coordinate_nodes[0].text or "").strip():
            raise ValueError("KML Polygon has no unique coordinate ring.")
        ring = []
        for token in (coordinate_nodes[0].text or "").split():
            parts = token.split(",")
            if len(parts) < 2:
                raise ValueError("KML coordinate tuple is invalid.")
            try:
                lon, lat = float(parts[0]), float(parts[1])
            except ValueError as exc:
                raise ValueError("KML coordinates must be numeric.") from exc
            if not (-180 <= lon <= 180 and -90 <= lat <= 90):
                raise ValueError("KML coordinates are outside valid longitude/latitude ranges.")
            ring.append((lon, lat))
        if len(ring) < 4 or len(ring) > 501 or ring[0] != ring[-1] or len(set(ring[:-1])) < 3:
            raise ValueError("KML outer ring must be closed and contain 3–500 distinct points.")
        validate_runner_url(self.page.url)
        route = urlparse(self.page.url).path.casefold().rstrip("/")
        if not (route == "/runner/plots" or route.startswith("/runner/plots/")):
            raise RuntimeError("KML boundary attachment is allowed only in the AKKPS Runner Plots workflow.")
        forms = self.page.locator("form:visible")
        if forms.count() != 1:
            raise RuntimeError("Expected exactly one visible Plot boundary form.")
        candidates = forms.first.locator('input[type="file"]')
        matches = []
        for index in range(candidates.count()):
            item = candidates.nth(index)
            labels = item.evaluate("(e) => [...(e.labels || [])].map(x => (x.innerText || '').replace(/\\s+/g, ' ').trim())")
            hints = [item.get_attribute(key) or "" for key in ("aria-label", "name", "id", "placeholder")]
            if label in labels or label in hints:
                matches.append(item)
        if len(matches) != 1:
            raise RuntimeError("Exact boundary KML file input is missing or ambiguous.")
        target = matches[0]
        accept = (target.get_attribute("accept") or "").casefold()
        if accept and not any(token in accept for token in (".kml", "application/vnd.google-earth.kml+xml", "*/*")):
            raise RuntimeError("This file input does not accept KML.")
        target.set_input_files(str(path))
        verified = target.evaluate("(e, name) => !!e.files && e.files.length === 1 && e.files[0].name === name", path.name)
        if not verified:
            raise RuntimeError("KML attachment could not be verified in the selected Plot form.")
        return {
            "field": label, "file_name": path.name, "file_attached": True,
            "portal_uploaded": False, "record_submitted": False,
            "next_step": "Review the visible map and use the portal's own Upload/Save control with separate confirmation."
        }

    def inspect_map_boundary(self):
        """Inspect visible map/drawing controls without reading coordinates or changing geometry."""
        validate_runner_url(self.page.url)
        data = self.page.evaluate("""() => {
          const visible = e => !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length));
          const clean = e => (e.innerText || e.getAttribute('aria-label') || e.getAttribute('title') || '')
            .replace(/\\s+/g, ' ').trim().slice(0, 100);
          const maps = [...document.querySelectorAll(
            '.leaflet-container, .mapboxgl-map, [role=application], [aria-label*=map i], canvas'
          )].filter(visible).slice(0, 12).map(e => ({
            kind: e.classList.contains('leaflet-container') ? 'leaflet' :
              e.classList.contains('mapboxgl-map') ? 'mapbox' :
              e.tagName.toLowerCase() === 'canvas' ? 'canvas' : 'application',
            label: clean(e), width: Math.round(e.getBoundingClientRect().width),
            height: Math.round(e.getBoundingClientRect().height)
          }));
          const controls = [...document.querySelectorAll('button,[role=button],a')].filter(visible)
            .map(clean).filter(x => /(polygon|boundary|draw|vertex|point|clear|undo|save|upload|kml|सीमा|बहुभुज|नक्शा|मानचित्र)/i.test(x))
            .slice(0, 60);
          const uploads = [...document.querySelectorAll('input[type=file]')].filter(visible).slice(0, 20).map(e => ({
            label: [...(e.labels || [])].map(x => clean(x)).filter(Boolean).join(' / ') ||
              e.getAttribute('aria-label') || e.name || e.id || 'unlabelled file input',
            accept: e.accept || ''
          }));
          const engine = window.L ? 'leaflet' :
            (window.mapboxgl ? 'mapbox-gl' : (window.google && window.google.maps ? 'google-maps' :
            (window.ol ? 'openlayers' : (window.maplibregl ? 'maplibre' : 'unknown'))));
          const polygonHints = document.querySelectorAll(
            '.leaflet-interactive, .mapboxgl-canvas, .ol-layer canvas'
          ).length;
          return {engine, maps, controls, uploads, polygonHints};
        }""")
        return {"page": _safe_runner_location(self.page.url)[1],
                "map_engine": data["engine"], "maps": data["maps"], "boundary_controls": data["controls"],
                "file_inputs": data.get("uploads", []), "visible_layer_hints": data.get("polygonHints", 0),
                "geometry_changed": False, "submitted": False,
                "note": "No coordinates or farmer records were read. KML may be attached to an exact visible Plot boundary field; portal drawing/save still requires live page verification."}

    def export_inventory(self, destination: str | Path):
        inventory = self.inventory()
        Path(destination).write_text(
            json.dumps(asdict(inventory), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return str(destination)

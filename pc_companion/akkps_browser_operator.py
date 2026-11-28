"""User-authorized AKKPS browser operator for the Shraddha AI Windows companion.

The operator uses a visible Chromium window and a persistent local browser profile.
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
SENSITIVE_TYPES = {"password", "hidden", "file"}
UNFILLABLE_TYPES = {"button", "submit", "reset", "image"}
SENSITIVE_LABEL = re.compile(r"password|passcode|otp|one.?time|pin|captcha|verification|पासवर्ड|ओटीपी|पिन", re.I)


def validate_akkps_url(url: str) -> str:
    """Allow only HTTPS pages on the two explicitly configured AKKPS domains."""
    parsed = urlparse(str(url or "").strip())
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
        raise ValueError("Only HTTPS pages on the approved AKKPS domains are allowed.")
    if parsed.username or parsed.password:
        raise ValueError("Credentials must not be embedded in a URL.")
    return url


@dataclass(frozen=True)
class FieldInfo:
    label: str
    name: str
    input_type: str
    required: bool
    disabled: bool
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


class AkkpsBrowserOperator:
    """Visible, human-in-the-loop browser session for AKKPS/TerraMark."""

    def __init__(self, profile_dir: str | Path):
        self.profile_dir = Path(profile_dir).expanduser().resolve()
        self._playwright = None
        self._context = None
        self._page = None

    @property
    def page(self):
        if self._page is None:
            raise RuntimeError("Browser is not open. Start it and sign in manually first.")
        return self._page

    def start(self, start_url: str = DEFAULT_HOME):
        validate_akkps_url(start_url)
        if self._page is not None:
            return self.inventory()
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise RuntimeError(
                "Browser automation is optional. Install pc_companion/requirements-browser.txt."
            ) from exc
        self.profile_dir.mkdir(parents=True, exist_ok=True)
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

    def close(self):
        if self._context:
            self._context.close()
        if self._playwright:
            self._playwright.stop()
        self._context = self._playwright = self._page = None

    def navigate(self, url: str):
        validate_akkps_url(url)
        self.page.goto(url, wait_until="domcontentloaded", timeout=45000)
        return self.inventory()

    def open_runner(self):
        current = urlparse(self.page.url)
        if current.hostname not in ALLOWED_HOSTS:
            raise RuntimeError("Sign in on the approved AKKPS portal before opening Runner.")
        target = f"https://{current.hostname}{RUNNER_PATH}"
        return self.navigate(target)

    def inventory(self) -> PageInventory:
        """Read visible page structure only; never collect input values or row contents."""
        data = self.page.evaluate("""() => {
          const visible = e => !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length));
          const clean = s => (s || '').replace(/\\s+/g, ' ').trim().slice(0, 160);
          const labelFor = e => {
            const id = e.id;
            const label = id ? document.querySelector('label[for="' + CSS.escape(id) + '"]') : null;
            return clean(label?.innerText || e.getAttribute('aria-label') || e.getAttribute('placeholder') || e.name || e.id);
          };
          const fields = [...document.querySelectorAll('input,select,textarea')]
            .filter(visible).map(e => ({
              label: labelFor(e), name: clean(e.name || e.id),
              type: clean(e.type || e.tagName.toLowerCase()).toLowerCase(),
              required: !!e.required, disabled: !!e.disabled,
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
            .map(f => [...f.querySelectorAll('button[type=submit],input[type=submit]')]
              .filter(visible).map(e => clean(e.innerText || e.value || e.getAttribute('aria-label'))).filter(Boolean));
          return {url: location.href, title: document.title, links, fields, buttons, tables, sections, forms};
        }""")
        validate_akkps_url(data["url"])
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
                options=tuple(item.get("options") or ()),
            ))
        return PageInventory(
            url=data["url"], title=data["title"],
            links=tuple(data["links"]), fields=tuple(safe_fields),
            buttons=tuple(data["buttons"]), table_headers=tuple(tuple(x) for x in data["tables"]),
            sections=tuple(data.get("sections", ())), forms=tuple(tuple(x) for x in data.get("forms", ())),
        )

    def fill_exact_label(self, label: str, value: str):
        """Fill one unique, visible, enabled non-sensitive field by exact accessible label."""
        wanted = " ".join(str(label).casefold().split())
        if not wanted or not isinstance(value, str):
            raise ValueError("A field label and text value are required.")
        visible_forms = self.page.locator("form:visible")
        if visible_forms.count() != 1:
            raise RuntimeError(f"Expected exactly one visible form; found {visible_forms.count()}.")
        matches = self.page.get_by_label(label, exact=True)
        count = matches.count()
        if count == 0:
            # Inventory labels may fall back to placeholder or the DOM name/id
            # when the site omits a real <label for=...> association.
            matches = self.page.get_by_placeholder(label, exact=True)
            count = matches.count()
        if count == 0:
            all_fields = self.page.locator("input,select,textarea")
            indices = all_fields.evaluate_all(
                "(elements, exact) => elements.map((e, i) => ({i, match: e.name === exact || e.id === exact}))"
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
            grouped.setdefault(field.label.casefold(), []).append(field)
        preview = []
        for label, value in values.items():
            matches = grouped.get(" ".join(str(label).casefold().split()), [])
            if len(matches) != 1:
                raise RuntimeError(f"Field {label!r} is missing or ambiguous in the current page inventory.")
            field = matches[0]
            if field.disabled:
                raise RuntimeError(f"Field {label!r} is disabled.")
            preview.append({"field": field.label, "value": str(value), "required": field.required})
        return {"page": inventory.title, "url": inventory.url, "changes": preview,
                "requires_user_confirmation": True, "submitted": False}

    def submit_after_confirmation(self, confirmation: str):
        """Submit only after an exact human confirmation; never auto-retry."""
        if confirmation != "SUBMIT AKKPS RECORD":
            raise PermissionError("Explicit confirmation phrase is required before submission.")
        # The actual submit control must be unique and visible; user confirms exact page.
        candidates = self.page.locator('button[type="submit"], input[type="submit"]')
        count = candidates.count()
        if count != 1:
            raise RuntimeError(f"Expected one submit control; found {count}. Refusing submission.")
        button = candidates.first
        if not button.is_visible() or not button.is_enabled():
            raise RuntimeError("Submit control is not available.")
        before_url = self.page.url
        button.click()
        # Do not wait for a navigation timeout: an SPA may have accepted the write
        # without navigating. Report the outcome as uncertain until a record is re-opened.
        # A click or toast alone is not server-side proof. Caller must verify the record.
        return {"clicked": True, "before_url": before_url, "after_url": self.page.url,
                "server_record_verified": False, "automatic_retry": False}

    def export_inventory(self, destination: str | Path):
        inventory = self.inventory()
        Path(destination).write_text(
            json.dumps(asdict(inventory), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return str(destination)

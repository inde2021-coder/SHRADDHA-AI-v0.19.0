"""Small Tk window for the optional AKKPS Playwright operator."""
from __future__ import annotations
import json
from dataclasses import asdict
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox, filedialog, simpledialog
try:
    from .akkps_browser_operator import AkkpsBrowserOperator, DEFAULT_HOME
except ImportError:
    from akkps_browser_operator import AkkpsBrowserOperator, DEFAULT_HOME

class AkkpsBrowserWindow:
    def __init__(self, parent, operator=None):
        self.window = tk.Toplevel(parent)
        self.window.title("Shraddha AI • AKKPS Website Operator")
        self.window.geometry("760x620")
        self.window.minsize(620, 480)
        self.operator = operator or AkkpsBrowserOperator(Path.home() / "AppData" / "Local" / "ShraddhaAI" / "AKKPSBrowserProfile")
        self.status = tk.StringVar(value="Browser not started")
        self.field_label = tk.StringVar()
        self.field_value = tk.StringVar()
        self._build()

    def _build(self):
        root = ttk.Frame(self.window, padding=14)
        root.pack(fill="both", expand=True)
        ttk.Label(root, text="AKKPS WEBSITE OPERATOR", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(root, text="Sign in yourself in the visible browser. Passwords and OTPs are never read or stored by Shraddha AI.", wraplength=700).pack(anchor="w", pady=(4, 10))
        bar = ttk.Frame(root); bar.pack(fill="x")
        ttk.Button(bar, text="Open AKKPS Login", command=self.start).pack(side="left")
        ttk.Button(bar, text="Open Runner", command=self.open_runner).pack(side="left", padx=6)
        ttk.Button(bar, text="Inspect Current Page", command=self.inspect).pack(side="left")
        ttk.Button(bar, text="Close Browser", command=self.close_browser).pack(side="left", padx=6)
        modules = ttk.Frame(root); modules.pack(fill="x", pady=(4, 0))
        ttk.Label(modules, text="Runner modules:").pack(side="left")
        for label in ("Farmers", "Plots", "AWD Reports", "Profile", "Data Sync"):
            ttk.Button(modules, text=label, command=lambda name=label: self.open_module(name)).pack(side="left", padx=3)
        ttk.Label(root, textvariable=self.status, foreground="#146c43").pack(anchor="w", pady=8)
        form = ttk.LabelFrame(root, text="Reviewed form entry", padding=8); form.pack(fill="x", pady=(0, 8))
        ttk.Label(form, text="Exact field label").grid(row=0, column=0, sticky="w")
        ttk.Entry(form, textvariable=self.field_label, width=28).grid(row=0, column=1, sticky="ew", padx=6)
        ttk.Label(form, text="Value").grid(row=0, column=2, sticky="w")
        ttk.Entry(form, textvariable=self.field_value, width=28).grid(row=0, column=3, sticky="ew", padx=6)
        ttk.Button(form, text="Preview", command=self.preview_fill).grid(row=0, column=4, padx=4)
        ttk.Button(form, text="Fill Exact Field", command=self.fill_field).grid(row=0, column=5, padx=4)
        ttk.Button(form, text="Submit Record…", command=self.submit).grid(row=0, column=6, padx=4)
        form.columnconfigure(1, weight=1); form.columnconfigure(3, weight=1)
        batch = ttk.LabelFrame(root, text="Batch field entry (JSON: exact visible label → value)", padding=8)
        batch.pack(fill="x", pady=(0, 8))
        self.batch_values = tk.Text(batch, height=4, wrap="word")
        self.batch_values.pack(fill="x", expand=True)
        self.batch_values.insert("1.0", "{\n  \"Exact visible field label\": \"Your verified value\"\n}")
        batch_actions = ttk.Frame(batch); batch_actions.pack(fill="x", pady=(5, 0))
        ttk.Button(batch_actions, text="Preview All Fields", command=self.preview_batch).pack(side="left")
        ttk.Button(batch_actions, text="Fill Reviewed Batch", command=self.fill_batch).pack(side="left", padx=6)
        ttk.Label(batch, text="Only exact visible text/select fields are supported. Review every value; this never submits the record.",
                  wraplength=700).pack(anchor="w", pady=(4, 0))
        workflows = ttk.LabelFrame(root, text="Page-specific workflows", padding=8)
        workflows.pack(fill="x", pady=(0, 8))
        ttk.Button(workflows, text="Add AWD repeated row…", command=self.add_awd_row).pack(side="left", padx=3)
        ttk.Button(workflows, text="Fill newest AWD row…", command=self.fill_awd_row).pack(side="left", padx=3)
        ttk.Button(workflows, text="Autocomplete…", command=self.autocomplete).pack(side="left", padx=3)
        ttk.Button(workflows, text="Attach document…", command=self.attach_document).pack(side="left", padx=3)
        ttk.Button(workflows, text="Upload attached document…", command=self.upload_document).pack(side="left", padx=3)
        ttk.Button(workflows, text="Inspect map boundary", command=self.inspect_map).pack(side="left", padx=3)
        ttk.Button(workflows, text="Start map Draw…", command=lambda: self.activate_map_tool("draw")).pack(side="left", padx=3)
        ttk.Button(workflows, text="Start map Edit…", command=lambda: self.activate_map_tool("edit")).pack(side="left", padx=3)
        ttk.Button(workflows, text="Verify map layers", command=self.verify_map_layers).pack(side="left", padx=3)
        ttk.Button(workflows, text="Attach boundary KML…", command=self.attach_boundary_kml).pack(side="left", padx=3)
        ttk.Button(workflows, text="Upload boundary to AKKPS…", command=self.upload_boundary_kml).pack(side="left", padx=3)
        self.output = tk.Text(root, wrap="word", height=22); self.output.pack(fill="both", expand=True)
        self.output.insert("end", "Open login → sign in manually → open Runner → inspect modules.\n")
        self.output.configure(state="disabled")
        self.window.protocol("WM_DELETE_WINDOW", self.close)

    def _show(self, value):
        self.output.configure(state="normal"); self.output.delete("1.0", "end")
        self.output.insert("end", json.dumps(value, ensure_ascii=False, indent=2)[:24000])
        self.output.configure(state="disabled")

    def start(self):
        try:
            inventory = self.operator.start(DEFAULT_HOME)
            self.status.set("Browser opened. Sign in manually, then choose Open Runner.")
            self._show(asdict(inventory))
        except Exception as exc:
            messagebox.showerror("AKKPS browser unavailable", str(exc), parent=self.window)

    def open_runner(self):
        try:
            inventory = self.operator.open_runner()
            self.status.set("Runner opened. Inspect the visible page to map its modules.")
            self._show(asdict(inventory))
        except Exception as exc:
            messagebox.showerror("Cannot open Runner", str(exc), parent=self.window)

    def open_module(self, module):
        try:
            inventory = self.operator.open_module(module)
            self.status.set(module + " module opened. Inspect the page to map visible fields.")
            self._show(asdict(inventory))
        except Exception as exc:
            messagebox.showerror("Cannot open AKKPS module", str(exc), parent=self.window)

    def inspect(self):
        try:
            inventory = self.operator.inventory()
            self.status.set("Page inventory captured. No form values or farmer records were read.")
            self._show(asdict(inventory))
        except Exception as exc:
            messagebox.showerror("Page inspection failed", str(exc), parent=self.window)

    def preview_fill(self):
        try:
            preview = self.operator.preview({self.field_label.get(): self.field_value.get()})
            self._show(preview)
            self.status.set("Preview only. Nothing has been entered or submitted.")
        except Exception as exc:
            messagebox.showerror("Preview rejected", str(exc), parent=self.window)

    def fill_field(self):
        try:
            preview = self.operator.preview({self.field_label.get(): self.field_value.get()})
            if not messagebox.askyesno("Confirm field entry", json.dumps(preview, ensure_ascii=False, indent=2)[:5000], parent=self.window):
                return
            result = self.operator.fill_exact_label(self.field_label.get(), self.field_value.get())
            self.status.set("Field filled. Record has NOT been submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Field entry rejected", str(exc), parent=self.window)

    def _batch_payload(self):
        try:
            values = json.loads(self.batch_values.get("1.0", "end").strip())
        except json.JSONDecodeError as exc:
            raise ValueError(f"Batch JSON is invalid: {exc.msg}") from exc
        if not isinstance(values, dict) or not values or any(
            not isinstance(k, str) or not isinstance(v, str) for k, v in values.items()
        ):
            raise ValueError("Enter a JSON object with exact field labels and text values.")
        return values

    def preview_batch(self):
        try:
            preview = self.operator.preview(self._batch_payload())
            self._show(preview)
            self.status.set("Batch preview only. No fields were changed.")
        except Exception as exc:
            messagebox.showerror("Batch preview rejected", str(exc), parent=self.window)

    def fill_batch(self):
        try:
            values = self._batch_payload()
            preview = self.operator.preview(values)
            if not messagebox.askyesno(
                "Confirm AKKPS batch field entry",
                "Review all exact labels and values below. This fills fields only; it does NOT submit.\\n\\n" +
                json.dumps(preview, ensure_ascii=False, indent=2)[:7000],
                parent=self.window
            ):
                return
            result = self.operator.fill_reviewed_fields(values)
            self.status.set(f"{len(result['filled_fields'])} fields filled. Record NOT submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Batch field entry rejected", str(exc), parent=self.window)


    def add_awd_row(self):
        try:
            section = simpledialog.askstring("AWD repeated entry", "Exact section name (e.g. Drying events, Fertilizer applications):", parent=self.window)
            if not section: return
            button = simpledialog.askstring("AWD repeated entry", "Exact visible Add-row button label:", parent=self.window)
            if not button: return
            preview = {"section": section, "button": button, "action": "Add one blank row only", "submit": False}
            if not messagebox.askyesno("Confirm AWD row", json.dumps(preview, ensure_ascii=False), parent=self.window): return
            result = self.operator.add_repeated_entry(section, button)
            self.status.set("AWD row added. Enter and review its values; record NOT submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("AWD row not added", str(exc), parent=self.window)

    def fill_awd_row(self):
        try:
            values = self._batch_payload()
            preview = self.operator.preview_latest_repeated_entry(values)
            if not messagebox.askyesno(
                "Confirm newest AWD row",
                "These values will be entered only into the row added most recently. No record is submitted.\\n\\n" +
                json.dumps(preview, ensure_ascii=False, indent=2)[:7000],
                parent=self.window
            ):
                return
            result = self.operator.fill_latest_repeated_entry(values)
            self.status.set("Newest AWD row filled. Record NOT submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("AWD row fill rejected", str(exc), parent=self.window)

    def autocomplete(self):
        try:
            label = simpledialog.askstring("Autocomplete", "Exact visible field label:", parent=self.window)
            if not label: return
            query = simpledialog.askstring("Autocomplete", "Search text (user-confirmed):", parent=self.window)
            if not query: return
            option = simpledialog.askstring("Autocomplete", "Exact suggestion to select:", parent=self.window)
            if not option: return
            preview = {"field": label, "search": query, "exact_suggestion": option}
            if not messagebox.askyesno("Confirm autocomplete selection", json.dumps(preview, ensure_ascii=False), parent=self.window): return
            result = self.operator.choose_autocomplete_option(label, query, option)
            self.status.set("Autocomplete choice selected and verified. Record NOT submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Autocomplete selection failed", str(exc), parent=self.window)

    def attach_document(self):
        try:
            label = simpledialog.askstring("Attach document", "Exact visible upload field label:", parent=self.window)
            if not label: return
            path = filedialog.askopenfilename(parent=self.window, title="Choose AKKPS document",
                filetypes=[("PDF and images", "*.pdf *.jpg *.jpeg *.png")])
            if not path: return
            preview = {"field": label, "file_name": Path(path).name, "max_size": "10 MB",
                       "action": "Attach to form only; upload/submit remains separate"}
            if not messagebox.askyesno("Confirm document attachment", json.dumps(preview, ensure_ascii=False), parent=self.window): return
            result = self.operator.attach_document(label, path)
            self.status.set("Document attached to form. Upload/record submission NOT performed.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Document attachment failed", str(exc), parent=self.window)

    def upload_document(self):
        try:
            label = simpledialog.askstring("Upload document", "Exact visible Upload button label:", parent=self.window)
            if not label: return
            preview = {"button": label, "action": "Upload selected document only; no record submit"}
            if not messagebox.askyesno("Confirm document upload", json.dumps(preview, ensure_ascii=False), parent=self.window): return
            result = self.operator.upload_attached_document_after_confirmation(label)
            if result.get("upload_verified"):
                self.status.set("Document upload response verified. Record NOT submitted.")
            else:
                self.status.set("Document upload outcome UNCERTAIN. Do not retry until checked.")
                messagebox.showwarning("Check document upload", "The upload may have reached the server. Check the document list/status before retrying.", parent=self.window)
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Document upload blocked", str(exc), parent=self.window)

    def inspect_map(self):
        try:
            result = self.operator.inspect_map_boundary()
            self.status.set("Map inspected only. No boundary coordinates changed or submitted.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Map inspection failed", str(exc), parent=self.window)

    def activate_map_tool(self, tool):
        try:
            label = simpledialog.askstring(
                "AKKPS map " + tool.title(),
                "Exact visible " + tool.title() + " button label on the current Plot map:",
                parent=self.window
            )
            if not label: return
            preview = {
                "tool": tool, "exact_button": label,
                "target": "one visible Plot map",
                "next_action": "You will place/move vertices manually on the map",
                "record_submit": False, "geometry_write_by_AI": False
            }
            if not messagebox.askyesno(
                "Confirm map tool",
                json.dumps(preview, ensure_ascii=False, indent=2),
                parent=self.window
            ): return
            result = self.operator.activate_map_tool(tool, label)
            self.status.set(
                "Map " + tool + " mode verified. Complete vertex work manually."
                if result.get("tool_active_verified")
                else "Map button clicked; active mode not verified. Check the page manually."
            )
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Map tool blocked", str(exc), parent=self.window)

    def verify_map_layers(self):
        try:
            result = self.operator.verify_map_boundary_layers()
            self.status.set("Map layer evidence refreshed. Server persistence is not yet verified.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Map layer verification failed", str(exc), parent=self.window)

    def attach_boundary_kml(self):
        try:
            label = simpledialog.askstring(
                "Attach Plot boundary KML",
                "Exact visible boundary/KML file field label on the AKKPS Plot page:",
                parent=self.window
            )
            if not label: return
            path = filedialog.askopenfilename(
                parent=self.window, title="Choose reviewed AKKPS boundary KML",
                filetypes=[("KML boundary", "*.kml")]
            )
            if not path: return
            preview = {
                "field": label, "file_name": Path(path).name,
                "action": "Attach KML to the visible Plot form only",
                "portal_upload": False, "record_submit": False
            }
            if not messagebox.askyesno(
                "Confirm boundary attachment",
                json.dumps(preview, ensure_ascii=False, indent=2),
                parent=self.window
            ): return
            result = self.operator.attach_boundary_kml(label, path)
            self.status.set("KML attached to Plot form. Review map; portal Upload/Save is still separate.")
            self._show(result)
        except Exception as exc:
            messagebox.showerror("Boundary KML attachment blocked", str(exc), parent=self.window)

    def upload_boundary_kml(self):
        try:
            label = simpledialog.askstring(
                "Upload AKKPS Plot boundary",
                "Exact visible non-submit Upload/Save Boundary button label:",
                parent=self.window
            )
            if not label: return
            preview = {
                "button": label,
                "action": "Upload the selected KML boundary only",
                "record_submit": False,
                "server_confirmation": "A 2xx same-origin response with explicit success and a record ID is required"
            }
            if not messagebox.askyesno(
                "Confirm AKKPS boundary upload",
                "This action may update the selected Plot boundary on AKKPS. It will not submit the whole Plot form.\n\n" +
                json.dumps(preview, ensure_ascii=False, indent=2),
                parent=self.window
            ): return
            result = self.operator.upload_boundary_kml_after_confirmation(label)
            if result.get("boundary_upload_verified"):
                record_id = str(result.get("record_id") or "")
                self.status.set("AKKPS boundary upload response verified • Record ID: " + record_id)
                if messagebox.askyesno(
                    "Re-open saved Plot map",
                    "AKKPS returned success and a record ID. Reload the same authenticated Plot page now and inspect the map again?",
                    parent=self.window
                ):
                    reopened = self.operator.reopen_plot_map_after_upload(record_id)
                    fingerprint = bool(reopened.get("rendered_polygon_fingerprint_matches"))
                    fingerprint_text = ("Pre-save/reopen SVG geometry fingerprint MATCHED (supporting evidence only)." if fingerprint
                                        else "No matching pre-save/reopen SVG geometry fingerprint was available.")
                    self.status.set("AKKPS Plot page re-opened. Polygon persistence remains unverified.")
                    self._show({"upload": result, "reopen_verification": reopened})
                    messagebox.showinfo(
                        "Plot map re-opened",
                        "Same Plot page reloaded. Map count: " + str(reopened.get("map_count_after_reload")) +
                        "\\nVisible vector-layer hints: " + str(reopened.get("visible_vector_layer_hints_after_reload")) +
                        "\\n" + fingerprint_text +
                        "\\n\\nThe fingerprint is not tied independently to the server Record ID. Visually compare the exact Plot boundary before marking it complete.",
                        parent=self.window
                    )
                else:
                    self._show(result)
            else:
                self.status.set("Boundary upload outcome UNCERTAIN. Do not retry until the Plot map is checked.")
                messagebox.showwarning(
                    "Verify Plot boundary",
                    "The request may have reached AKKPS, but a successful response with a record ID was not verified. Re-open the Plot map before any retry.",
                    parent=self.window
                )
                self._show(result)
        except Exception as exc:
            messagebox.showerror("Boundary upload blocked", str(exc), parent=self.window)

    def submit(self):
        if not messagebox.askyesno("Confirm AKKPS submission",
                "Submit the currently open AKKPS form? Check the page and all values first. This action may create a real record.",
                parent=self.window):
            return
        try:
            result = self.operator.submit_after_confirmation("SUBMIT AKKPS RECORD")
            if result.get("server_record_verified"):
                self.status.set("AKKPS server response verified • Record ID: " + str(result.get("record_id")))
                self._show(result)
                messagebox.showinfo("AKKPS record response verified", "A successful same-origin write response included a record ID. Please still confirm the record appears in the AKKPS list.", parent=self.window)
            else:
                self.status.set("Submit outcome UNCERTAIN. Do not submit again until checked.")
                self._show(result)
                messagebox.showwarning("Verify saved record", "The result is uncertain. The submit may have reached the server. Re-open the relevant AKKPS list and check before any retry.", parent=self.window)
        except Exception as exc:
            messagebox.showerror("Submission blocked", str(exc), parent=self.window)

    def close_browser(self):
        try: self.operator.close()
        finally: self.status.set("Browser closed.")

    def close(self):
        self.close_browser(); self.window.destroy()

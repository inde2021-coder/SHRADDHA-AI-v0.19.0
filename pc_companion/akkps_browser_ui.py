"""Small Tk window for the optional AKKPS Playwright operator."""
from __future__ import annotations
import json
from dataclasses import asdict
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox
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
            self._show(inventory.__dict__)
        except Exception as exc:
            messagebox.showerror("Cannot open Runner", str(exc), parent=self.window)

    def inspect(self):
        try:
            inventory = self.operator.inventory()
            self.status.set("Page inventory captured. No form values or farmer records were read.")
            self._show(inventory.__dict__)
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

    def submit(self):
        if not messagebox.askyesno("Confirm AKKPS submission",
                "Submit the currently open AKKPS form? Check the page and all values first. This action may create a real record.",
                parent=self.window):
            return
        try:
            result = self.operator.submit_after_confirmation("SUBMIT AKKPS RECORD")
            self.status.set("Submit clicked. Server-side record save is NOT yet verified.")
            self._show(result)
            messagebox.showwarning("Verify saved record", "The submit click is not proof of saving. Re-open the relevant AKKPS list and verify the record. Do not submit again until checked.", parent=self.window)
        except Exception as exc:
            messagebox.showerror("Submission blocked", str(exc), parent=self.window)

    def close_browser(self):
        try: self.operator.close()
        finally: self.status.set("Browser closed.")

    def close(self):
        self.close_browser(); self.window.destroy()

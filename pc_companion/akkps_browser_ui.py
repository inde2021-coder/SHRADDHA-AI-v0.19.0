"""Small Tk window for the optional AKKPS Playwright operator."""
from __future__ import annotations
import json
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox

try:
    from .akkps_browser_operator import AkkpsBrowserOperator, DEFAULT_HOME
except ImportError:
    from akkps_browser_operator import AkkpsBrowserOperator, DEFAULT_HOME


class AkkpsBrowserWindow:
    def __init__(self, parent):
        self.window = tk.Toplevel(parent)
        self.window.title("Shraddha AI • AKKPS Website Operator")
        self.window.geometry("760x620")
        self.window.minsize(620, 480)
        self.operator = AkkpsBrowserOperator(
            Path.home() / "AppData" / "Local" / "ShraddhaAI" / "AKKPSBrowserProfile"
        )
        self.status = tk.StringVar(value="Browser not started")
        self._build()

    def _build(self):
        root = ttk.Frame(self.window, padding=14)
        root.pack(fill="both", expand=True)
        ttk.Label(root, text="AKKPS WEBSITE OPERATOR", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(root, text="Sign in yourself in the visible browser. Passwords and OTPs are never read or stored by Shraddha AI.",
                  wraplength=700).pack(anchor="w", pady=(4, 10))
        bar = ttk.Frame(root)
        bar.pack(fill="x")
        ttk.Button(bar, text="Open AKKPS Login", command=self.start).pack(side="left")
        ttk.Button(bar, text="Open Runner", command=self.open_runner).pack(side="left", padx=6)
        ttk.Button(bar, text="Inspect Current Page", command=self.inspect).pack(side="left")
        ttk.Button(bar, text="Close Browser", command=self.close_browser).pack(side="left", padx=6)
        ttk.Label(root, textvariable=self.status, foreground="#146c43").pack(anchor="w", pady=8)
        self.output = tk.Text(root, wrap="word", height=25)
        self.output.pack(fill="both", expand=True)
        self.output.insert("end", "Workflow: open login → sign in manually → open Runner → inspect modules.\n")
        self.output.configure(state="disabled")
        self.window.protocol("WM_DELETE_WINDOW", self.close)

    def _show(self, value):
        self.output.configure(state="normal")
        self.output.delete("1.0", "end")
        self.output.insert("end", json.dumps(value, ensure_ascii=False, indent=2)[:24000])
        self.output.configure(state="disabled")

    def start(self):
        try:
            inventory = self.operator.start(DEFAULT_HOME)
            self.status.set("Browser opened. Sign in manually, then choose Open Runner.")
            self._show(inventory.__dict__)
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

    def close_browser(self):
        try:
            self.operator.close()
        finally:
            self.status.set("Browser closed.")

    def close(self):
        self.close_browser()
        self.window.destroy()

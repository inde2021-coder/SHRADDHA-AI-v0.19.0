"""Tkinter Language Lab for optional local-only inference on the paired Windows PC."""
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox


class LanguageLabDialog:
    def __init__(self, parent):
        self.window = tk.Toplevel(parent)
        self.window.title("Shraddha AI • Offline Language Lab")
        self.window.geometry("760x620")
        self.window.minsize(650, 520)
        self.window.transient(parent)
        self.status = tk.StringVar(value="Local-only mode. Install licensed model files and configure environment paths first.")
        ttk.Label(self.window, text="SHRADDHA AI  /  OFFLINE LANGUAGE LAB",
                  font=("Segoe UI", 13, "bold")).pack(anchor="w", padx=16, pady=(14, 4))
        ttk.Label(self.window, text="Runs on this Windows PC • no cloud fallback • Pawari remains community-data required",
                  wraplength=710).pack(anchor="w", padx=16, pady=(0, 10))
        tabs = ttk.Notebook(self.window)
        tabs.pack(fill="both", expand=True, padx=16, pady=8)
        self._build_asr(tabs)
        self._build_translation(tabs)
        self._build_tts(tabs)
        ttk.Label(self.window, textvariable=self.status, wraplength=710).pack(fill="x", padx=16, pady=(4, 14))

    def _run(self, task, success):
        self.status.set("Loading local model and processing…")
        def worker():
            try:
                result = task()
                self.window.after(0, lambda: (self.status.set("Completed locally."), success(result)))
            except Exception as exc:
                message = str(exc)[:700]
                self.window.after(0, lambda: self.status.set("Not completed: " + message))
        threading.Thread(target=worker, daemon=True).start()

    def _build_asr(self, tabs):
        page = ttk.Frame(tabs, padding=14)
        tabs.add(page, text="Speech → Text")
        language = tk.StringVar(value="hi")
        wav = tk.StringVar()
        output = tk.Text(page, height=12, wrap="word")
        ttk.Label(page, text="Language").pack(anchor="w")
        ttk.Combobox(page, textvariable=language, values=("hi", "en", "mr"), state="readonly").pack(anchor="w", pady=4)
        ttk.Label(page, text="IndicConformer: Hindi / Marathi • English: NeMo FastConformer; Sanskrit/Pawari are disabled until verified").pack(anchor="w", pady=4)
        row = ttk.Frame(page); row.pack(fill="x", pady=6)
        ttk.Entry(row, textvariable=wav).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Choose 16 kHz WAV", command=lambda: wav.set(filedialog.askopenfilename(filetypes=[("WAV audio", "*.wav")]))).pack(side="left", padx=6)
        def transcribe():
            lang, path = language.get(), wav.get()
            if not path:
                self.status.set("Choose a WAV recording first.")
                return
            def task():
                try:
                    from .language_runtime import IndicConformerRuntime
                except (ImportError, ValueError):
                    from language_runtime import IndicConformerRuntime
                return IndicConformerRuntime(lang).transcribe_wav(path)
            self._run(task, lambda value: (output.delete("1.0", "end"), output.insert("1.0", value)))
        ttk.Button(page, text="TRANSCRIBE OFFLINE", command=transcribe).pack(anchor="w", pady=6)
        output.pack(fill="both", expand=True, pady=8)

    def _build_translation(self, tabs):
        page = ttk.Frame(tabs, padding=14)
        tabs.add(page, text="Translate")
        source, target = tk.StringVar(value="hi"), tk.StringVar(value="en")
        row = ttk.Frame(page); row.pack(fill="x", pady=4)
        ttk.Label(row, text="From").pack(side="left")
        ttk.Combobox(row, textvariable=source, values=("hi", "en", "mr"), state="readonly", width=10).pack(side="left", padx=6)
        ttk.Label(row, text="To").pack(side="left")
        ttk.Combobox(row, textvariable=target, values=("hi", "en", "mr"), state="readonly", width=10).pack(side="left", padx=6)
        ttk.Label(page, text="IndicTrans2 local checkpoint directory is configured in SHRADDHA_TRANSLATION_MODEL_DIR.").pack(anchor="w", pady=4)
        incoming = tk.Text(page, height=8, wrap="word"); incoming.pack(fill="both", expand=True, pady=6)
        outgoing = tk.Text(page, height=8, wrap="word"); outgoing.pack(fill="both", expand=True, pady=6)
        def translate():
            source_code, target_code = source.get(), target.get()
            text_value = incoming.get("1.0", "end").strip()
            import os
            model_dir = os.environ.get("SHRADDHA_TRANSLATION_MODEL_DIR", "").strip()
            if not model_dir:
                self.status.set("Set SHRADDHA_TRANSLATION_MODEL_DIR to a local IndicTrans2 checkpoint.")
                return
            if not text_value:
                self.status.set("Enter text to translate.")
                return
            def task():
                try:
                    from .language_runtime import IndicTrans2Runtime
                except (ImportError, ValueError):
                    from language_runtime import IndicTrans2Runtime
                return IndicTrans2Runtime(model_dir).translate(text_value, source_code, target_code)
            self._run(task, lambda value: (outgoing.delete("1.0", "end"), outgoing.insert("1.0", value)))
        ttk.Button(page, text="TRANSLATE OFFLINE", command=translate).pack(anchor="w", pady=6)

    def _build_tts(self, tabs):
        page = ttk.Frame(tabs, padding=14)
        tabs.add(page, text="Text → Speech")
        language = tk.StringVar(value="hi")
        reference = tk.StringVar()
        output = tk.StringVar()
        ttk.Label(page, text="IndicF5 supported in this adapter: Hindi and Marathi.").pack(anchor="w")
        ttk.Combobox(page, textvariable=language, values=("hi", "mr"), state="readonly", width=10).pack(anchor="w", pady=5)
        text = tk.Text(page, height=6, wrap="word"); text.pack(fill="x", pady=6)
        ttk.Label(page, text="Consented reference voice audio (required by IndicF5)").pack(anchor="w")
        row = ttk.Frame(page); row.pack(fill="x", pady=4)
        ttk.Entry(row, textvariable=reference).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Choose audio", command=lambda: reference.set(filedialog.askopenfilename(filetypes=[("Audio", "*.wav *.flac")]))).pack(side="left", padx=6)
        ttk.Label(page, text="Exact transcript of the reference audio").pack(anchor="w", pady=(6, 0))
        transcript = ttk.Entry(page); transcript.pack(fill="x", pady=4)
        voice_consent = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            page,
            text="I have explicit permission from the reference voice owner to synthesize with this recording.",
            variable=voice_consent
        ).pack(anchor="w", pady=4)
        row2 = ttk.Frame(page); row2.pack(fill="x", pady=4)
        ttk.Entry(row2, textvariable=output).pack(side="left", fill="x", expand=True)
        ttk.Button(row2, text="Save WAV as", command=lambda: output.set(filedialog.asksaveasfilename(defaultextension=".wav", filetypes=[("WAV audio", "*.wav")]))).pack(side="left", padx=6)
        def synthesize():
            text_value, lang = text.get("1.0", "end").strip(), language.get()
            reference_path, reference_text, output_path = reference.get(), transcript.get(), output.get()
            consented = voice_consent.get()
            if not consented:
                self.status.set("Explicit permission from the reference voice owner is required.")
                return
            if not output_path:
                self.status.set("Choose an output WAV path.")
                return
            def task():
                try:
                    from .language_runtime import IndicF5Runtime
                except (ImportError, ValueError):
                    from language_runtime import IndicF5Runtime
                return str(IndicF5Runtime().synthesize(
                    text_value, lang, reference_path, reference_text, output_path,
                    explicit_reference_voice_consent=consented
                ))
            self._run(task, lambda value: messagebox.showinfo("Shraddha AI", "Audio saved locally:\n" + value, parent=self.window))
        ttk.Button(page, text="SYNTHESIZE OFFLINE", command=synthesize).pack(anchor="w", pady=8)

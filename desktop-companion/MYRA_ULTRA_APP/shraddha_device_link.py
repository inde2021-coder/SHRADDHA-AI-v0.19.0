"""MYRA dock panel for the official consent-gated Shraddha PC companion."""
from __future__ import annotations

import platform
from pathlib import Path

from PyQt5.QtCore import QProcess, QProcessEnvironment, Qt
from PyQt5.QtWidgets import (
    QApplication, QDockWidget, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
    QPushButton, QPlainTextEdit, QMessageBox, QMainWindow
)

from shraddha_link_core import build_launch_spec


class ShraddhaDeviceLinkDock(QDockWidget):
    def __init__(self, parent=None):
        super().__init__("Shraddha Device Link", parent)
        self.setObjectName("shraddhaDeviceLinkDock")
        self.setAllowedAreas(Qt.LeftDockWidgetArea | Qt.RightDockWidgetArea)
        self.process = QProcess(self)
        self.process.setProcessChannelMode(QProcess.MergedChannels)
        self.process.readyReadStandardOutput.connect(self._read_output)
        self.process.finished.connect(self._process_finished)
        self.process.errorOccurred.connect(self._process_error)
        self.process.stateChanged.connect(self._process_state_changed)
        app = QApplication.instance()
        if app is not None:
            app.aboutToQuit.connect(self.stop_companion)

        body = QWidget(self)
        layout = QVBoxLayout(body)
        layout.addWidget(QLabel("Connect MYRA ULTRA to your Shraddha Android device or other paired PCs."))
        layout.addWidget(QLabel("Shraddha relay HTTPS URL"))
        self.relay = QLineEdit()
        self.relay.setPlaceholderText("https://your-shraddha-relay.example")
        layout.addWidget(self.relay)
        layout.addWidget(QLabel("One-time six-digit pairing code (blank = use saved PC pairing)"))
        self.code = QLineEdit()
        self.code.setEchoMode(QLineEdit.Password)
        self.code.setMaxLength(6)
        self.code.setPlaceholderText("123456")
        layout.addWidget(self.code)
        layout.addWidget(QLabel("This PC's device name"))
        self.device_name = QLineEdit(platform.node() or "MYRA Desktop")
        layout.addWidget(self.device_name)

        buttons = QHBoxLayout()
        self.start_button = QPushButton("Connect securely")
        self.stop_button = QPushButton("Disconnect")
        self.stop_button.setEnabled(False)
        buttons.addWidget(self.start_button)
        buttons.addWidget(self.stop_button)
        layout.addLayout(buttons)
        self.status = QLabel("Disconnected")
        layout.addWidget(self.status)
        self.output = QPlainTextEdit()
        self.output.setReadOnly(True)
        self.output.setMaximumBlockCount(500)
        self.output.setPlaceholderText("Companion connection messages appear here.")
        layout.addWidget(self.output)
        layout.addWidget(QLabel(
            "Safety: the PC user must approve each control session and each action. "
            "Typing, OCR, screen sharing and file access remain off unless separately enabled "
            "in the PC companion. Close this panel to hide it; use Disconnect to stop the link."
        ))
        self.setWidget(body)
        self.start_button.clicked.connect(self.start_companion)
        self.stop_button.clicked.connect(self.stop_companion)

    def start_companion(self):
        if self.process.state() != QProcess.NotRunning:
            return
        try:
            spec = build_launch_spec(
                Path(__file__).resolve().parent,
                self.relay.text(),
                self.code.text(),
                self.device_name.text(),
            )
        except Exception as exc:
            QMessageBox.warning(self, "Shraddha Device Link", str(exc))
            return
        env = QProcessEnvironment.systemEnvironment()
        for key in list(env.keys()):
            upper = key.upper()
            if any(marker in upper for marker in ("API_KEY", "TOKEN", "PASSWORD", "SECRET", "CREDENTIAL", "AUTH")):
                env.remove(key)
        for key, value in spec["environment"].items():
            env.insert(str(key), str(value))
        self.process.setProcessEnvironment(env)
        self.process.setProgram(spec["program"])
        self.process.setArguments(spec["arguments"])
        self.output.appendPlainText("Starting consent-gated Shraddha PC companion…")
        self.process.start()
        self.code.clear()

    def stop_companion(self):
        if self.process.state() == QProcess.NotRunning:
            self.status.setText("Disconnected")
            return
        self.process.terminate()
        if not self.process.waitForFinished(2500):
            self.process.kill()
            self.process.waitForFinished(1000)

    def _read_output(self):
        raw = bytes(self.process.readAllStandardOutput()).decode("utf-8", errors="replace")
        if raw:
            self.output.appendPlainText(raw.rstrip())
            self.status.setText("Companion running")

    def _process_finished(self, _code, _status):
        self.status.setText("Disconnected")
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)
        self.output.appendPlainText("Shraddha PC companion stopped.")

    def _process_state_changed(self, state):
        running = state != QProcess.NotRunning
        self.start_button.setEnabled(not running)
        self.stop_button.setEnabled(running)
        self.status.setText("Companion running" if running else "Disconnected")

    def _process_error(self, _error):
        self.status.setText("Companion could not start")
        self.output.appendPlainText(self.process.errorString())
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)

    def showEvent(self, event):
        super().showEvent(event)
        running = self.process.state() != QProcess.NotRunning
        self.start_button.setEnabled(not running)
        self.stop_button.setEnabled(running)


def attach_device_link(window):
    if not isinstance(window, QMainWindow):
        raise TypeError("MYRA main window must be a QMainWindow.")
    existing = getattr(window, "_shraddha_device_link_dock", None)
    if existing is not None:
        return existing
    dock = ShraddhaDeviceLinkDock(window)
    window.addDockWidget(Qt.RightDockWidgetArea, dock)
    menu = window.menuBar().addMenu("Device Link")
    action = menu.addAction("Show Shraddha ↔ MYRA connection")
    action.triggered.connect(dock.show)
    action.triggered.connect(dock.raise_)
    window._shraddha_device_link_dock = dock
    return dock

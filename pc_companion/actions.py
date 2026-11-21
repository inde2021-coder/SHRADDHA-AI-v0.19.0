"""Allowlisted Windows UI actions. No shell execution or arbitrary process launch."""
def execute_action(action, pyautogui, clipboard, open_shortcut):
    kind = action["kind"]
    try:
        width, height = pyautogui.size()
        if kind == "tap":
            pyautogui.click(round(action["x"] * (width - 1)), round(action["y"] * (height - 1)))
            return True, "Mouse click completed."
        if kind == "swipe":
            x1, y1 = round(action["x1"] * (width - 1)), round(action["y1"] * (height - 1))
            x2, y2 = round(action["x2"] * (width - 1)), round(action["y2"] * (height - 1))
            pyautogui.moveTo(x1, y1)
            pyautogui.mouseDown()
            try:
                pyautogui.moveTo(x2, y2, duration=0.35)
            finally:
                pyautogui.mouseUp()
            return True, "Mouse drag completed."
        if kind == "type":
            clipboard.copy(action["text"])
            pyautogui.hotkey("ctrl", "v")
            return True, "Text pasted into the focused control."
        if kind == "open_app":
            return (True, "Application shortcut opened.") if open_shortcut(action["appName"]) else (False, "No matching Start Menu shortcut found.")
        if kind == "home":
            pyautogui.hotkey("win", "d")
            return True, "Desktop shown."
        if kind == "back":
            pyautogui.hotkey("alt", "left")
            return True, "Back shortcut sent."
        if kind == "scroll":
            pyautogui.scroll(action["amount"])
            return True, "Scroll completed."
        if kind == "click_text":
            return False, "Text-based click is not supported on Windows yet; use screen coordinates."
        return False, "Unsupported action."
    except pyautogui.FailSafeException:
        return False, "PyAutoGUI fail-safe triggered; move the pointer away from the top-left corner and re-approve."
    except Exception as exc:
        return False, f"Action failed: {str(exc)[:180]}"

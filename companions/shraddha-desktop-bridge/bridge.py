#!/usr/bin/env python3
"""Consent-first Shraddha desktop relay bridge. No remote control unless explicitly approved."""
import argparse, asyncio, json, os, platform, subprocess, sys, urllib.request
from urllib.parse import urlparse

try:
    import websockets
except ImportError:
    websockets = None

APP_ALLOWLIST = {
    "windows": {"notepad": ["notepad.exe"], "calculator": ["calc.exe"], "explorer": ["explorer.exe"]},
    "linux": {"terminal": ["x-terminal-emulator"], "calculator": ["gnome-calculator"], "files": ["xdg-open", os.path.expanduser("~")]},
    "darwin": {"terminal": ["open", "-a", "Terminal"], "calculator": ["open", "-a", "Calculator"], "files": ["open", os.path.expanduser("~")]},
}

def post_json(base, path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers={"Content-Type":"application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.loads(response.read().decode())

def ask_yes_no(prompt):
    try:
        answer = input(prompt + " [y/N] ").strip().lower()
        return answer in ("y", "yes")
    except (EOFError, KeyboardInterrupt):
        return False

def execute_action(action):
    kind = action.get("kind")
    if kind == "type":
        import pyautogui
        text = str(action.get("text", ""))[:1000]
        pyautogui.write(text, interval=0.01)
        return "Typed text into the currently focused window"
    if kind == "tap":
        import pyautogui
        x, y = int(action.get("x", -1)), int(action.get("y", -1))
        width, height = pyautogui.size()
        if not (0 <= x < width and 0 <= y < height): raise ValueError("Coordinates outside desktop bounds")
        pyautogui.click(x, y); return f"Clicked at {x},{y}"
    if kind == "swipe":
        import pyautogui
        x1,y1,x2,y2 = [int(action.get(k,-1)) for k in ("x1","y1","x2","y2")]
        width,height=pyautogui.size()
        if not all(0 <= x < width and 0 <= y < height for x,y in ((x1,y1),(x2,y2))): raise ValueError("Swipe outside desktop bounds")
        pyautogui.moveTo(x1,y1); pyautogui.dragTo(x2,y2,duration=0.35); return "Desktop drag completed"
    if kind == "scroll":
        import pyautogui
        amount=max(-10,min(10,int(action.get("amount",0))))
        pyautogui.scroll(amount); return "Scrolled focused desktop window"
    if kind == "open_app":
        name=str(action.get("name","")).strip().lower()
        os_key = "windows" if os.name == "nt" else ("darwin" if sys.platform == "darwin" else "linux")
        command=APP_ALLOWLIST.get(os_key,{}).get(name)
        if not command: raise ValueError("App is not in the desktop allowlist")
        subprocess.Popen(command, shell=False); return f"Opened approved app: {name}"
    raise ValueError("Action kind is not supported by desktop bridge")

async def run(args):
    if websockets is None: raise RuntimeError("Install requirements.txt first")
    base=args.relay.rstrip("/")
    parsed=urlparse(base)
    if parsed.scheme != "https": raise ValueError("Relay must use HTTPS/WSS")
    joined=post_json(base,"/api/device/join",{"deviceName":args.name,"pairingCode":args.code})
    ws_url="wss://"+parsed.netloc+"/ws?sessionId="+joined["sessionId"]+"&deviceId="+joined["deviceId"]
    print("Paired as",args.name,". Keep this terminal visible. Ctrl+C disconnects.")
    async with websockets.connect(ws_url, extra_headers={"Authorization":"Bearer "+joined["deviceToken"]}, max_size=3*1024*1024, ping_interval=25) as ws:
        await ws.send(json.dumps({"type":"permission_update","permissions":{"remote_control":bool(args.allow_control)}}))
        print("Remote control sharing:", "enabled (approval still required)" if args.allow_control else "disabled")
        async for raw in ws:
            if isinstance(raw, bytes): continue
            try: msg=json.loads(raw)
            except Exception: continue
            kind=msg.get("type")
            if kind == "connected":
                print("Relay connected. Paired devices:",len(msg.get("devices",[])))
            elif kind == "control_request":
                who=msg.get("requesterName","paired device")
                mins=max(1,min(5,int(msg.get("requestedMinutes",5))))
                approved=args.allow_control and ask_yes_no(f"Allow {who} to control this desktop for up to {mins} minutes?")
                await ws.send(json.dumps({"type":"control_response","requesterDeviceId":msg.get("fromDeviceId"),"approved":approved,"minutes":mins}))
                print("Session", "approved" if approved else "denied")
            elif kind == "remote_action":
                action=msg.get("action") or {}
                if not args.allow_control:
                    print("Ignored remote action: desktop control sharing is disabled")
                    continue
                if not ask_yes_no(f"Execute remote desktop action {action.get('kind')}? Review payload: {json.dumps(action)[:300]}"):
                    print("Action denied locally")
                    continue
                try: print("Action result:",execute_action(action))
                except Exception as exc: print("Action failed:",str(exc))
            elif kind == "control_mode": print("Relay control mode:",msg.get("mode"))
            elif kind == "peer_status": print("Peer",msg.get("name"),"online" if msg.get("online") else "offline")
            elif kind == "chat": print("Paired device:",msg.get("message",""))

if __name__ == "__main__":
    parser=argparse.ArgumentParser(description="Shraddha AI desktop bridge")
    parser.add_argument("--relay",required=True,help="HTTPS base URL of your Shraddha relay")
    parser.add_argument("--code",required=True,help="Six-digit pairing code from Shraddha AI")
    parser.add_argument("--name",default=platform.node() or "Shraddha Desktop")
    parser.add_argument("--allow-control",action="store_true",help="Allow incoming control requests; still asks approval for each session and action")
    args=parser.parse_args()
    try: asyncio.run(run(args))
    except KeyboardInterrupt: print("Disconnected by user")
    except Exception as exc: print("Bridge error:",exc); sys.exit(1)

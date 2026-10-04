#!/usr/bin/env python3
"""A real Home Assistant for CI's end-to-end runs.

  ha.py config <dir>        write configuration.yaml (demo integration + what Homebase reads)
  ha.py deps                install the camera / stream requirements before Home Assistant starts (see deps())
  ha.py setup               wait for Home Assistant, onboard an owner, add floors / areas, energy prefs and
                            favorites, and create a non-admin "phone" user (Homebase needs no admin rights)
  ha.py code <base>         an OAuth code for the phone user, client_id <base>/, redirect <base>/<path>
  ha.py token               a long-lived token for the phone user
  ha.py state <entity_id>   the entity's current state

Only the Python standard library plus aiohttp (a Home Assistant dependency).
"""
import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HA = os.environ.get("HA_URL", "http://127.0.0.1:8123")
STATE_FILE = os.environ.get("HA_E2E_STATE", os.path.expanduser("~/ha-e2e.json"))
OWNER = ("owner", "owner-pass-1234")
PHONE = ("phone", "phone-pass-1234")
LOCAL_CLIENT = HA + "/"

CONFIG = """
homeassistant:
  name: CI Home
  latitude: 52.37
  longitude: 4.89
  elevation: 0
  unit_system: metric
  time_zone: Europe/Amsterdam
  currency: EUR
  country: NL
http:
  server_port: 8123
auth:
api:
websocket_api:
config:
frontend:
history:
recorder:
  purge_keep_days: 2
energy:
usage_prediction:
demo:
logger:
  default: warning
"""


def deps():
    """The demo integration imports its camera platform (camera -> stream -> numpy) while Home Assistant is
    still installing camera's requirements at startup; the import loses that race, the demo config entry
    fails without a retry and no demo entity appears. Installing them first avoids it. The versions come
    from Home Assistant's own manifests."""
    import pathlib
    import subprocess
    import homeassistant
    base = pathlib.Path(homeassistant.__file__).parent / "components"
    reqs = []
    for name in ("camera", "stream"):
        reqs += json.loads((base / name / "manifest.json").read_text())["requirements"]
    print("installing", " ".join(reqs))
    subprocess.check_call([sys.executable, "-m", "uv", "pip", "install", "-q", "--python", sys.executable, *reqs])


def http(method, path, data=None, form=None, token=None, timeout=20):
    headers = {}
    body = None
    if form is not None:
        body = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(HA + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")


def login_code(username, password, client_id, redirect_uri):
    status, flow = http("POST", "/auth/login_flow", {"client_id": client_id, "handler": ["homeassistant", None], "redirect_uri": redirect_uri})
    if status != 200:
        raise SystemExit(f"login_flow: {status} {flow}")
    status, done = http("POST", f"/auth/login_flow/{flow['flow_id']}", {"client_id": client_id, "username": username, "password": password})
    if status != 200 or done.get("type") != "create_entry":
        raise SystemExit(f"login_flow step: {status} {done}")
    return done["result"]


def exchange(code, client_id):
    status, tok = http("POST", "/auth/token", form={"grant_type": "authorization_code", "code": code, "client_id": client_id})
    if status != 200:
        raise SystemExit(f"token: {status} {tok}")
    return tok


class Ws:
    def __init__(self, token):
        self.token = token
        self.id = 0

    async def __aenter__(self):
        import aiohttp
        self.session = aiohttp.ClientSession()
        self.ws = await self.session.ws_connect(HA.replace("http", "ws", 1) + "/api/websocket", max_msg_size=0)
        await self.ws.receive_json()
        await self.ws.send_json({"type": "auth", "access_token": self.token})
        msg = await self.ws.receive_json()
        if msg.get("type") != "auth_ok":
            raise SystemExit(f"ws auth: {msg}")
        return self

    async def __aexit__(self, *exc):
        await self.ws.close()
        await self.session.close()

    async def call(self, type_, **fields):
        self.id += 1
        await self.ws.send_json({"id": self.id, "type": type_, **fields})
        while True:
            msg = await self.ws.receive_json(timeout=60)
            if msg.get("id") == self.id and msg.get("type") == "result":
                if not msg.get("success"):
                    print(f"  {type_}: {msg.get('error')}", file=sys.stderr)
                    return None
                return msg.get("result")


def wait_ready(limit=1200):
    start = time.time()
    while time.time() - start < limit:
        try:
            status, _ = http("GET", "/api/onboarding", timeout=5)
            if status in (200, 401, 404):
                return
        except Exception:
            pass
        time.sleep(3)
    raise SystemExit("Home Assistant did not start")


def save_state(**kw):
    st = load_state()
    st.update(kw)
    with open(STATE_FILE, "w") as f:
        json.dump(st, f)


def load_state():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except OSError:
        return {}


async def configure(admin):
    async with Ws(admin) as ws:
        # wait until Home Assistant has started and the demo entities exist
        states = []
        for _ in range(150):
            cfg = await ws.call("get_config") or {}
            states = await ws.call("get_states") or []
            if cfg.get("state") == "RUNNING" and sum(1 for s in states if s["entity_id"].startswith("light.")) >= 2:
                break
            await asyncio.sleep(2)
        print(f"  Home Assistant {cfg.get('version')}, {len(states)} states")
        if sum(1 for s in states if s["entity_id"].startswith("light.")) < 2:
            raise SystemExit("the demo integration did not load (no demo lights); see 'Error setting up entry Demo' in the Home Assistant log")
        floors = {f["name"]: f["floor_id"] for f in (await ws.call("config/floor_registry/list") or [])}
        for name, level in (("Ground floor", 0), ("First floor", 1)):
            if name not in floors:
                r = await ws.call("config/floor_registry/create", name=name, level=level)
                if r:
                    floors[name] = r["floor_id"]
        # onboarding already adds a few areas: reuse them, put them on floors
        existing = {a["name"].lower(): a for a in (await ws.call("config/area_registry/list") or [])}
        areas = {}
        for name, floor in (("Living room", "Ground floor"), ("Kitchen", "Ground floor"), ("Bedroom", "First floor"), ("Office", "First floor")):
            a = existing.get(name.lower())
            if a:
                r = await ws.call("config/area_registry/update", area_id=a["area_id"], floor_id=floors.get(floor))
            else:
                r = await ws.call("config/area_registry/create", name=name, floor_id=floors.get(floor))
            if r:
                areas[name] = r["area_id"]
        devices = await ws.call("config/device_registry/list") or []
        order = list(areas.values())
        hints = {"living": "Living room", "kitchen": "Kitchen", "bed": "Bedroom", "office": "Office"}
        for i, d in enumerate(devices):
            name = (d.get("name_by_user") or d.get("name") or "").lower()
            area = next((areas.get(a) for k, a in hints.items() if k in name), None) or (order[i % len(order)] if order else None)
            if area and not d.get("area_id"):
                await ws.call("config/device_registry/update", device_id=d["id"], area_id=area)
        print(f"  {len(floors)} floors, {len(areas)} areas, {len(devices)} devices placed")

        stats = await ws.call("recorder/list_statistic_ids", statistic_type="sum") or []
        kwh = [s["statistic_id"] for s in stats if (s.get("statistics_unit_of_measurement") or s.get("display_unit_of_measurement")) == "kWh"]
        print(f"  energy statistics: {kwh}")
        power = [s["entity_id"] for s in states if s["attributes"].get("device_class") == "power"]
        if kwh:
            grid = {"type": "grid", "flow_from": [{"stat_energy_from": kwh[0], "stat_cost": None, "entity_energy_price": None, "number_energy_price": None}],
                    "flow_to": [], "cost_adjustment_day": 0}
            if power:
                grid["power"] = [{"stat_rate": power[0]}]
            await ws.call("energy/save_prefs", energy_sources=[grid], device_consumption=[])
            print(f"  energy prefs: grid {kwh[0]}, power {power[:1]}")

        rank = {d: i for i, d in enumerate(("light", "switch", "lock", "fan", "climate", "cover"))}
        pick = sorted((s["entity_id"] for s in states if s["entity_id"].split(".")[0] in rank), key=lambda e: rank[e.split(".")[0]])[:6]
        await ws.call("frontend/set_system_data", key="home", value={"favorite_entities": pick})
        print(f"  favorites: {pick}")

        ids = [s["entity_id"] for s in states]
        first = lambda d: next((e for e in ids if e.startswith(d + ".")), None)
        cards = [{"type": "tile", "entity": e} for e in ids if e.startswith(("light.", "lock."))][:6]
        if first("climate"):
            cards.append({"type": "thermostat", "entity": first("climate")})
        if first("weather"):
            cards.append({"type": "weather-forecast", "entity": first("weather")})
        cards.append({"type": "entities", "title": "Covers", "entities": [e for e in ids if e.startswith("cover.")]})
        await ws.call("lovelace/config/save", config={"views": [{"title": "Home", "cards": cards}]})
        print(f"  dashboard with {len(cards)} cards")

        users = await ws.call("config/auth/list") or []
        if not any(u.get("username") == PHONE[0] for u in users):
            user = await ws.call("config/auth/create", name="Phone", group_ids=["system-users"], local_only=False)
            await ws.call("config/auth_provider/homeassistant/create", user_id=user["user"]["id"], username=PHONE[0], password=PHONE[1])
        print("  phone user (not an admin) ready")


def setup():
    wait_ready()
    status, steps = http("GET", "/api/onboarding")
    admin = None
    if isinstance(steps, list) and any(s.get("step") == "user" and not s.get("done") for s in steps):
        status, r = http("POST", "/api/onboarding/users",
                         {"client_id": LOCAL_CLIENT, "name": "CI Owner", "username": OWNER[0], "password": OWNER[1], "language": "en"})
        if status != 200:
            raise SystemExit(f"onboarding user: {status} {r}")
        admin = exchange(r["auth_code"], LOCAL_CLIENT)["access_token"]
        for step, body in (("core_config", {}), ("analytics", {}), ("integration", {"client_id": LOCAL_CLIENT, "redirect_uri": LOCAL_CLIENT})):
            http("POST", f"/api/onboarding/{step}", body, token=admin)
    else:
        admin = exchange(login_code(*OWNER, LOCAL_CLIENT, LOCAL_CLIENT), LOCAL_CLIENT)["access_token"]
    print("Home Assistant is up; owner onboarded")
    asyncio.run(configure(admin))
    save_state(admin=admin)


async def long_lived():
    access = exchange(login_code(*PHONE, LOCAL_CLIENT, LOCAL_CLIENT), LOCAL_CLIENT)["access_token"]
    async with Ws(access) as ws:
        return await ws.call("auth/long_lived_access_token", client_name=f"Homebase CI {int(time.time())}", lifespan=30)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "config":
        d = sys.argv[2]
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "configuration.yaml"), "w") as f:
            f.write(CONFIG)
    elif cmd == "deps":
        deps()
    elif cmd == "setup":
        setup()
    elif cmd == "code":
        base = sys.argv[2].rstrip("/")
        path = sys.argv[3] if len(sys.argv) > 3 else "hawidgets/callback"
        print(login_code(*PHONE, base + "/", f"{base}/{path}"))
    elif cmd == "token":
        print(asyncio.run(long_lived()))
    elif cmd == "state":
        admin = load_state()["admin"]
        status, s = http("GET", f"/api/states/{sys.argv[2]}", token=admin)
        print(s.get("state") if isinstance(s, dict) else s)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()

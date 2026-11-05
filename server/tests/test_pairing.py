import json

from flipdesk.config import Config
from flipdesk.pairing import MAX_PIN_FAILURES, PairingManager, lan_addresses
from flipdesk.testclient import parse_qr


def make(tmp_path):
    cfg = Config(tmp_path)
    return cfg, PairingManager(cfg, "ab" * 32, 8765)


def test_secret_pairs_once(tmp_path):
    cfg, pm = make(tmp_path)
    s = pm.open()
    token, why = pm.try_pair({"device_id": "dev-12345", "device_name": "Flip", "secret": s.secret})
    assert token and why == ""
    # one-time: a second use of the same secret fails
    again, why = pm.try_pair({"device_id": "dev-99999", "secret": s.secret})
    assert again is None and "not open" in why


def test_token_is_stored_hashed_and_authenticates(tmp_path):
    cfg, pm = make(tmp_path)
    s = pm.open()
    token, _ = pm.try_pair({"device_id": "dev-12345", "secret": s.secret})
    assert token not in (tmp_path / "devices.json").read_text()
    assert pm.authenticate({"device_id": "dev-12345", "token": token})
    assert pm.authenticate({"device_id": "dev-12345", "token": token + "x"}) is None
    assert pm.authenticate({"device_id": "other-dev", "token": token}) is None
    assert pm.authenticate({"device_id": "dev-12345"}) is None


def test_pin_pairing_and_lockout(tmp_path):
    cfg, pm = make(tmp_path)
    s = pm.open()
    wrong = "000000" if s.pin != "000000" else "111111"
    for _ in range(MAX_PIN_FAILURES):
        tok, why = pm.try_pair({"device_id": "dev-12345", "pin": wrong})
        assert tok is None
    # after too many guesses the code is invalidated, even the right PIN fails now
    tok, why = pm.try_pair({"device_id": "dev-12345", "pin": s.pin})
    assert tok is None and "not open" in why
    s2 = pm.open()
    tok, _ = pm.try_pair({"device_id": "dev-12345", "pin": s2.pin})
    assert tok


def test_pairing_closed_or_expired(tmp_path):
    cfg, pm = make(tmp_path)
    assert pm.try_pair({"device_id": "dev-12345", "pin": "123456"})[0] is None
    s = pm.open(duration=-1)
    assert pm.try_pair({"device_id": "dev-12345", "secret": s.secret})[0] is None
    assert pm.active() is None


def test_bad_device_id(tmp_path):
    cfg, pm = make(tmp_path)
    s = pm.open()
    assert pm.try_pair({"device_id": "x", "secret": s.secret})[0] is None


def test_qr_payload_parses(tmp_path):
    cfg, pm = make(tmp_path)
    s = pm.open()
    info = parse_qr(pm.qr_payload(s))
    assert info["fp"] == "ab" * 32 and info["port"] == 8765 and info["secret"] == s.secret
    assert info["hosts"] == lan_addresses() and info["server_id"] == cfg.server_id


def test_launchers_file_is_validated(tmp_path):
    cfg = Config(tmp_path)
    assert any(l["id"] == "notepad" for l in cfg.launchers())
    cfg.launchers_path.write_text(json.dumps({"launchers": [
        {"id": "ok", "name": "Ok", "type": "url", "target": "https://x"},
        {"id": "bad", "name": "Bad", "type": "rootkit", "target": "x"},
        {"name": "no id"},
    ]}))
    assert [l["id"] for l in cfg.launchers()] == ["ok"]

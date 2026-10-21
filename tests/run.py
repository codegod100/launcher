#!/usr/bin/env python3
"""Serve web/ on a free port and run tests/e2e.cjs against it (`pixi run test`)."""

import os
import subprocess
import sys
import threading

from ultra_tool import make_server

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

srv = make_server(os.path.join(ROOT, "web"), 0, quiet=True)
threading.Thread(target=srv.serve_forever, daemon=True).start()
r = subprocess.run(["node", os.path.join(ROOT, "tests", "e2e.cjs")], cwd=ROOT,
                   env={**os.environ, "BASE": f"http://localhost:{srv.server_address[1]}"})
srv.shutdown()
sys.exit(r.returncode)

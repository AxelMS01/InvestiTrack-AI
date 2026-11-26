"""Create the local Compose .env once with unique random credentials."""

import os
from pathlib import Path
from secrets import token_urlsafe

root = Path(__file__).resolve().parent.parent
template = (root / ".env.example").read_text(encoding="utf-8")
try:
    with open(root / ".env", "x", encoding="utf-8",
              opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
        for line in template.splitlines():
            if line.endswith("=GENERATE_LOCALLY"):
                line = line.split("=", 1)[0] + "=" + token_urlsafe(32)
            output.write(line + "\n")
except FileExistsError:
    print("Se conserva el archivo .env existente.")
else:
    print("Se creó .env con credenciales locales independientes.")

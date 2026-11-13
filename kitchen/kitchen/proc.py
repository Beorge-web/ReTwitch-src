"""Thin subprocess wrapper with consistent logging."""
import os
import subprocess
from pathlib import Path


def run(
    args,
    cwd: Path | None = None,
    check: bool = True,
    env: dict | None = None,
) -> subprocess.CompletedProcess:
    printable = " ".join(str(a) for a in args)
    print(f"$ {printable}" + (f"   (cwd={cwd})" if cwd else ""))
    merged_env = None
    if env is not None:
        merged_env = {**os.environ, **env}
    return subprocess.run(args, cwd=cwd, check=check, env=merged_env)

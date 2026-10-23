"""Runs the C engine on a photo and saves the person mask: mask.py yseg_test model.bin photo w h out.raw"""
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

exe, model, photo, w, h, out = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), sys.argv[6]
img = Image.open(photo).convert("RGB").resize((w, h), Image.BILINEAR)
with tempfile.TemporaryDirectory() as d:
    np.asarray(img, np.uint8).tofile(f"{d}/in.rgb")
    r = subprocess.run([exe, model, f"{d}/in.rgb", str(w), str(h), "1", f"{d}/out.bin"], capture_output=True, text=True)
    print(r.stdout.strip().splitlines()[0], "|", r.stdout.strip().splitlines()[-1])
    raw = np.fromfile(f"{d}/out.bin", np.uint8)
raw[-w * h:].tofile(out)

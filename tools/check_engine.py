"""Checks the C engine against the torch reference of the same op list, and against Ultralytics.

Usage: check_engine.py <weights.pt> <model.bin> <yseg_test binary> <photo> [w h]
"""
import os
import subprocess
import sys
import tempfile

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lower import Graph, run_ref  # noqa: E402


def main():
    from ultralytics import YOLO

    pt, binf, exe, photo = sys.argv[1:5]
    w, h = (int(sys.argv[5]), int(sys.argv[6])) if len(sys.argv) > 6 else (480, 320)
    yolo = YOLO(pt)
    model = yolo.model.float().eval().fuse()
    g = Graph()
    g.model(model)
    img = Image.open(photo).convert("RGB").resize((w, h), Image.BILINEAR)
    rgb = np.asarray(img, np.uint8)
    with tempfile.TemporaryDirectory() as d:
        rgb.tofile(f"{d}/in.rgb")
        out = subprocess.run([exe, binf, f"{d}/in.rgb", str(w), str(h), "1", f"{d}/out.bin"], capture_output=True, text=True)
        print(out.stdout, out.stderr)
        raw = np.fromfile(f"{d}/out.bin", np.uint8)
    x = torch.from_numpy(rgb.astype(np.float32) / 255).permute(2, 0, 1)[None]
    with torch.no_grad():
        want = run_ref(g, x, half=True)
    off = 0
    for i, t in enumerate(want):
        n = t.numel()
        got = np.frombuffer(raw[off:off + n * 4].tobytes(), np.float32).reshape(t.shape)
        off += n * 4
        print(f"output {i} {tuple(t.shape)}: max error {np.abs(got - t.numpy()).max():.5f}")
    mask = raw[off:].reshape(h, w)
    Image.fromarray(mask).save(os.path.splitext(photo)[0] + "_cmask.png")
    r = yolo.predict(np.asarray(img)[..., ::-1], imgsz=(h, w), classes=[0], conf=0.2, verbose=False)[0]
    print("ultralytics people:", 0 if r.masks is None else len(r.masks))
    if r.masks is not None:
        um = r.masks.data.max(0)[0].numpy() > 0.5
        cm = np.asarray(Image.fromarray(mask).resize((um.shape[1], um.shape[0]), Image.BILINEAR)) > 127
        print(f"mask agreement (IoU): {(um & cm).sum() / max(1, (um | cm).sum()):.3f}, pixels ultra={um.sum()} engine={cm.sum()}")


main()

"""Flattens an Ultralytics YOLO11-seg model into a list of simple ops for the camera's C engine.

Usage: lower.py <weights.pt> <out.bin> [--check photo.jpg]

Ops (tensors are float CHW, batch 1; tensor 0 is the RGB input, 0..1):
  CONV    in out cin cout k stride pad groups act    + fp16 weights [cout][cin/groups][k][k], fp32 bias
  ADD     a b out
  CONCAT  n ids... out                                (channels)
  SLICE   in c0 c1 out                                (channels c0..c1-1)
  MAXPOOL in out k pad                                (stride 1)
  UP2     in out                                      (nearest, x2)
  CONVT2  in out cin cout                             + fp16 weights [cin][cout][2][2], fp32 bias
  ATTN    qkv out v heads key_dim head_dim            (YOLO11 PSA attention, see Attention.forward)

Outputs, in order: for each of the three scales, box (64 ch, DFL), class logits (80 COCO classes, or
only "person" with --person-only), mask coefficients (32 ch); then the mask protos.
"""
import struct
import sys

import numpy as np
import torch
import torch.nn.functional as F

OP_CONV, OP_ADD, OP_CONCAT, OP_SLICE, OP_MAXPOOL, OP_UP2, OP_CONVT2, OP_ATTN = range(1, 9)
PERSON = 0
CLASSES = slice(PERSON, PERSON + 1) if "--person-only" in sys.argv else slice(None)


class Graph:
    def __init__(self):
        self.ops = []  # (type, ints, weights or None, bias or None)
        self.channels = [3]  # channels of each tensor

    def tensor(self, c):
        self.channels.append(c)
        return len(self.channels) - 1

    def conv(self, x, conv, act, out_slice=None):
        """conv is an nn.Conv2d (BN already fused); act: 1 for SiLU, 0 for none."""
        w = conv.weight.detach().float()
        b = conv.bias.detach().float() if conv.bias is not None else torch.zeros(w.shape[0])
        if out_slice is not None:
            w, b = w[out_slice], b[out_slice]
        cout, cin_g, k, _ = w.shape
        cin = cin_g * conv.groups
        assert conv.stride[0] == conv.stride[1] and conv.padding[0] == conv.padding[1] and conv.dilation == (1, 1)
        assert conv.groups in (1, cin) and (conv.groups == 1 or cout == cin)
        y = self.tensor(cout)
        self.ops.append((OP_CONV, [x, y, cin, cout, k, conv.stride[0], conv.padding[0], conv.groups, act], w, b))
        return y

    def Conv(self, x, m):
        """Ultralytics Conv: conv (+ fused BN) + SiLU or identity."""
        act = 1 if isinstance(m.act, torch.nn.SiLU) else 0
        assert act or isinstance(m.act, torch.nn.Identity), m.act
        assert not hasattr(m, "bn"), "fuse() the model first"
        return self.conv(x, m.conv, act)

    def add(self, a, b):
        y = self.tensor(self.channels[a])
        self.ops.append((OP_ADD, [a, b, y], None, None))
        return y

    def concat(self, xs):
        y = self.tensor(sum(self.channels[x] for x in xs))
        self.ops.append((OP_CONCAT, [len(xs)] + list(xs) + [y], None, None))
        return y

    def slice(self, x, c0, c1):
        y = self.tensor(c1 - c0)
        self.ops.append((OP_SLICE, [x, c0, c1, y], None, None))
        return y

    def maxpool(self, x, k):
        y = self.tensor(self.channels[x])
        self.ops.append((OP_MAXPOOL, [x, y, k, k // 2], None, None))
        return y

    def up2(self, x):
        y = self.tensor(self.channels[x])
        self.ops.append((OP_UP2, [x, y], None, None))
        return y

    def convt2(self, x, m):
        w = m.weight.detach().float()
        cin, cout = w.shape[0], w.shape[1]
        assert m.kernel_size == (2, 2) and m.stride == (2, 2) and m.padding == (0, 0)
        y = self.tensor(cout)
        self.ops.append((OP_CONVT2, [x, y, cin, cout], w, m.bias.detach().float()))
        return y

    # ---- Ultralytics blocks ----

    def Bottleneck(self, x, m):
        y = self.Conv(self.Conv(x, m.cv1), m.cv2)
        return self.add(x, y) if m.add else y

    def C3k(self, x, m):
        a = self.Conv(x, m.cv1)
        for b in m.m:
            a = self.Bottleneck(a, b)
        return self.Conv(self.concat([a, self.Conv(x, m.cv2)]), m.cv3)

    def C3k2(self, x, m):
        t = self.Conv(x, m.cv1)
        c = self.channels[t] // 2
        ys = [self.slice(t, 0, c), self.slice(t, c, 2 * c)]
        for b in m.m:
            name = type(b).__name__
            if name == "C3k":
                ys.append(self.C3k(ys[-1], b))
            elif name == "Bottleneck":
                ys.append(self.Bottleneck(ys[-1], b))
            else:
                raise ValueError(name)
        return self.Conv(self.concat(ys), m.cv2)

    def SPPF(self, x, m):
        ys = [self.Conv(x, m.cv1)]
        for _ in range(getattr(m, "n", 3)):
            ys.append(self.maxpool(ys[-1], m.m.kernel_size))
        y = self.Conv(self.concat(ys), m.cv2)
        return self.add(y, x) if getattr(m, "add", False) else y

    def Attention(self, x, m):
        qkv = self.Conv(x, m.qkv)
        c = self.channels[x]
        o = self.tensor(c)
        v = self.tensor(c)
        self.ops.append((OP_ATTN, [qkv, o, v, m.num_heads, m.key_dim, m.head_dim], None, None))
        return self.Conv(self.add(o, self.Conv(v, m.pe)), m.proj)

    def PSABlock(self, x, m):
        a = self.Attention(x, m.attn)
        x = self.add(x, a) if m.add else a
        f = self.Conv(self.Conv(x, m.ffn[0]), m.ffn[1])
        return self.add(x, f) if m.add else f

    def C2PSA(self, x, m):
        t = self.Conv(x, m.cv1)
        a, b = self.slice(t, 0, m.c), self.slice(t, m.c, 2 * m.c)
        for blk in m.m:
            b = self.PSABlock(b, blk)
        return self.Conv(self.concat([a, b]), m.cv2)

    def seq_conv(self, x, seq, last_slice=None):
        """A head branch: Conv/DWConv blocks (possibly nested in Sequential), then a plain Conv2d."""
        for i, s in enumerate(seq):
            if isinstance(s, torch.nn.Sequential):
                for t in s:
                    x = self.Conv(x, t)
            elif isinstance(s, torch.nn.Conv2d):
                x = self.conv(x, s, 0, last_slice if i == len(seq) - 1 else None)
            else:
                x = self.Conv(x, s)
        return x

    # ---- whole model ----

    def model(self, model):
        saved = {}
        layers = model.model
        x = 0
        outs = []
        for i, m in enumerate(layers):
            name = type(m).__name__
            src = [x if f == -1 else saved[f] for f in (m.f if isinstance(m.f, list) else [m.f])]
            if name == "Conv":
                x = self.Conv(src[0], m)
            elif name in ("C3k2", "SPPF", "C2PSA"):
                x = getattr(self, name)(src[0], m)
            elif name == "Upsample":
                assert m.scale_factor == 2 and m.mode == "nearest"
                x = self.up2(src[0])
            elif name == "Concat":
                x = self.concat(src)
            elif name == "Segment":
                for lvl, f in enumerate(src):
                    outs.append(self.seq_conv(f, m.cv2[lvl]))
                    outs.append(self.seq_conv(f, m.cv3[lvl], CLASSES))
                    outs.append(self.seq_conv(f, m.cv4[lvl]))
                p = m.proto
                outs.append(self.Conv(self.Conv(self.convt2(self.Conv(src[0], p.cv1), p.upsample), p.cv2), p.cv3))
            else:
                raise ValueError(name)
            saved[i] = x
        self.outputs = outs

    def write(self, path, strides, reg_max):
        with open(path, "wb") as f:
            f.write(b"YSEG")
            f.write(struct.pack("<7i", 1, len(self.channels), len(self.ops), len(self.outputs), reg_max, 0, 0))
            f.write(struct.pack("<%di" % len(self.outputs), *self.outputs))
            f.write(struct.pack("<3i", *strides))
            for t, ints, w, b in self.ops:
                f.write(struct.pack("<2i", t, len(ints)))
                f.write(struct.pack("<%di" % len(ints), *ints))
                if w is not None:
                    f.write(w.numpy().astype("<f2").tobytes())
                    f.write(b.numpy().astype("<f4").tobytes())


def run_ref(g, x, half=True):
    """Runs the op list with torch, as the C engine will (weights rounded to fp16 if half)."""
    t = {0: x}
    for op, a, w, b in g.ops:
        if w is not None and half:
            w = w.half().float()
        if op == OP_CONV:
            i, o, cin, cout, k, s, p, groups, act = a
            y = F.conv2d(t[i], w, b, s, p, 1, groups)
            t[o] = F.silu(y) if act else y
        elif op == OP_ADD:
            t[a[2]] = t[a[0]] + t[a[1]]
        elif op == OP_CONCAT:
            n = a[0]
            t[a[n + 1]] = torch.cat([t[j] for j in a[1:n + 1]], 1)
        elif op == OP_SLICE:
            t[a[3]] = t[a[0]][:, a[1]:a[2]]
        elif op == OP_MAXPOOL:
            t[a[1]] = F.max_pool2d(t[a[0]], a[2], 1, a[3])
        elif op == OP_UP2:
            t[a[1]] = F.interpolate(t[a[0]], scale_factor=2, mode="nearest")
        elif op == OP_CONVT2:
            t[a[1]] = F.conv_transpose2d(t[a[0]], w, b, 2)
        elif op == OP_ATTN:
            qkv, o, v, heads, kd, hd = a
            q_ = t[qkv]
            B, _, H, W = q_.shape
            N = H * W
            q, k, vv = q_.view(B, heads, kd * 2 + hd, N).split([kd, kd, hd], dim=2)
            attn = ((q * kd ** -0.5).transpose(-2, -1) @ k).softmax(dim=-1)
            C = heads * hd
            t[o] = (vv @ attn.transpose(-2, -1)).reshape(B, C, H, W)
            t[v] = vv.reshape(B, C, H, W)
    return [t[i] for i in g.outputs]


def main():
    from ultralytics import YOLO

    yolo = YOLO(sys.argv[1])
    model = yolo.model.float().eval().fuse()
    head = model.model[-1]
    g = Graph()
    g.model(model)
    strides = [int(s) for s in head.stride]
    g.write(sys.argv[2], strides, head.reg_max)
    n_w = sum(w.numel() for _, _, w, _ in g.ops if w is not None)
    print(f"{len(g.ops)} ops, {len(g.channels)} tensors, {n_w} weights, strides {strides}, reg_max {head.reg_max}")

    if "--check" in sys.argv:
        from PIL import Image
        photo = sys.argv[sys.argv.index("--check") + 1]
        img = Image.open(photo).convert("RGB").resize((480, 320), Image.BILINEAR)
        x = torch.from_numpy(np.asarray(img, np.float32) / 255).permute(2, 0, 1)[None]
        with torch.no_grad():
            # the real model's raw head outputs, for comparison
            feats, saved, y = [], {}, x
            for i, m in enumerate(model.model[:-1]):
                inp = y if m.f == -1 else [y if f == -1 else saved[f] for f in m.f]
                y = m(inp)
                saved[i] = y
            feats = [saved[f] for f in head.f]
            want = []
            for lvl in range(3):
                want += [head.cv2[lvl](feats[lvl]), head.cv3[lvl](feats[lvl])[:, CLASSES], head.cv4[lvl](feats[lvl])]
            want.append(head.proto(feats[0]))
            for half in (False, True):
                got = run_ref(g, x, half)
                errs = [float((a - b).abs().max()) for a, b in zip(got, want)]
                print(("fp16 weights" if half else "fp32 weights") + " max error per output:", " ".join(f"{e:.4f}" for e in errs))


if __name__ == "__main__":
    main()

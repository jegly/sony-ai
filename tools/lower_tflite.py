"""Converts a small float TFLite vision model (MediaPipe's face detector or face landmarker) into the op
list the camera's C engine runs (see lower.py for the format; this adds the ops those models need).

Usage: lower_tflite.py <model.tflite> <out.bin> --scale S --offset O [--check]

The engine feeds the input as rgb / 255 * scale + offset (face detector: scale 2, offset -1; landmarks:
scale 1, offset 0). Tensors are CHW in the engine and NHWC in TFLite; weights are transposed here.
Model outputs are the graph outputs the converted ops make directly, then the tensors that feed the
graph's RESHAPE / LOGISTIC tail, in graph order, so the app decodes them itself.

Extra ops (beyond lower.py's):
  TCONV    in out cin cout k stride same groups act   + fp16 weights [cout][cin/groups][k][k], fp32 bias
  PRELU    in out c                                   + fp32 alpha[c]
  PAD      in out c_before c_after top bottom left right
  MAXPOOL2 in out k stride same
  ADD      a b out act                                (act: 0 none, 1 SiLU, 2 ReLU, 3 ReLU6)
"""
import struct
import sys

import numpy as np
import tflite
from ai_edge_litert.interpreter import Interpreter, OpResolverType

OP_ADD, OP_CONCAT = 2, 3
OP_TCONV, OP_PRELU, OP_PAD, OP_MAXPOOL2 = 9, 10, 11, 12
ACT = {0: 0, 1: 2, 3: 3}  # tflite ActivationFunctionType NONE, RELU, RELU6 -> engine codes


def builtin_name(code):
    for k, v in tflite.BuiltinOperator.__dict__.items():
        if v == code:
            return k
    return str(code)


def main():
    path, out = sys.argv[1], sys.argv[2]
    scale = float(sys.argv[sys.argv.index("--scale") + 1])
    offset = float(sys.argv[sys.argv.index("--offset") + 1])
    buf = open(path, "rb").read()
    model = tflite.Model.GetRootAsModel(buf, 0)
    g = model.Subgraphs(0)

    it = Interpreter(model_path=path, experimental_preserve_all_tensors=True,
                     experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    h, w = inp["shape"][1], inp["shape"][2]
    rng = np.random.default_rng(0)
    rgb = rng.integers(0, 256, (h, w, 3), dtype=np.uint8)
    x = (rgb[None].astype(np.float32) / 255 * scale + offset).astype(np.float32)
    it.set_tensor(inp["index"], x)
    it.invoke()

    def const(i):
        return it.get_tensor(i)

    # engine tensor ids: TFLite tensor index -> our id
    ids = {g.Inputs(0): 0}
    channels = [3]
    ops = []
    outputs = []

    def new(ti, c):
        ids[ti] = len(channels)
        channels.append(c)
        return ids[ti]

    consumers = {}
    for k in range(g.OperatorsLength()):
        op = g.Operators(k)
        for j in range(op.InputsLength()):
            consumers.setdefault(op.Inputs(j), []).append(k)

    for k in range(g.OperatorsLength()):
        op = g.Operators(k)
        name = builtin_name(model.OperatorCodes(op.OpcodeIndex()).BuiltinCode())
        ins = [op.Inputs(j) for j in range(op.InputsLength())]
        outs = [op.Outputs(j) for j in range(op.OutputsLength())]
        shape = g.Tensors(outs[0]).ShapeAsNumpy() if outs else None
        if name == "DEQUANTIZE":
            continue
        if name in ("RESHAPE", "LOGISTIC"):
            if ins[0] in ids and ids[ins[0]] not in outputs:
                outputs.append(ids[ins[0]])
            continue
        if name in ("CONV_2D", "DEPTHWISE_CONV_2D"):
            o = tflite.Conv2DOptions() if name == "CONV_2D" else tflite.DepthwiseConv2DOptions()
            t = op.BuiltinOptions()
            o.Init(t.Bytes, t.Pos)
            assert o.StrideH() == o.StrideW() and o.DilationHFactor() == 1 and o.DilationWFactor() == 1
            wt = const(ins[1]).astype(np.float32)
            bias = const(ins[2]).astype(np.float32) if len(ins) > 2 and ins[2] >= 0 else np.zeros(shape[3], np.float32)
            if name == "CONV_2D":
                cout, kh, kw, cin = wt.shape
                wc = wt.transpose(0, 3, 1, 2)  # [O][I][kh][kw]
                groups = 1
            else:
                _, kh, kw, cout = wt.shape
                cin = cout
                assert o.DepthMultiplier() == 1
                wc = wt.transpose(3, 0, 1, 2)  # [C][1][kh][kw]
                groups = cin
            assert kh == kw
            same = 1 if o.Padding() == tflite.Padding.SAME else 0
            act = ACT[o.FusedActivationFunction()]
            y = new(outs[0], cout)
            ops.append((OP_TCONV, [ids[ins[0]], y, cin, cout, kh, o.StrideH(), same, groups, act], wc, bias))
        elif name == "PRELU":
            alpha = const(ins[1]).astype(np.float32).reshape(-1)
            c = channels[ids[ins[0]]]
            if alpha.size == 1:
                alpha = np.repeat(alpha, c)
            assert alpha.size == c, (alpha.shape, c)
            y = new(outs[0], c)
            ops.append((OP_PRELU, [ids[ins[0]], y, c], None, alpha))
        elif name == "ADD":
            o = tflite.AddOptions()
            t = op.BuiltinOptions()
            o.Init(t.Bytes, t.Pos)
            y = new(outs[0], channels[ids[ins[0]]])
            ops.append((OP_ADD, [ids[ins[0]], ids[ins[1]], y, ACT[o.FusedActivationFunction()]], None, None))
        elif name == "RELU":
            # a lone ReLU: an add of nothing with ReLU is clumsy, so use PRELU with alpha 0
            c = channels[ids[ins[0]]]
            y = new(outs[0], c)
            ops.append((OP_PRELU, [ids[ins[0]], y, c], None, np.zeros(c, np.float32)))
        elif name == "PAD":
            p = const(ins[1]).astype(np.int64)  # [[0,0],[top,bottom],[left,right],[cb,ca]]
            assert p[0].tolist() == [0, 0]
            y = new(outs[0], channels[ids[ins[0]]] + int(p[3][0] + p[3][1]))
            ops.append((OP_PAD, [ids[ins[0]], y, int(p[3][0]), int(p[3][1]), int(p[1][0]), int(p[1][1]), int(p[2][0]), int(p[2][1])], None, None))
        elif name == "MAX_POOL_2D":
            o = tflite.Pool2DOptions()
            t = op.BuiltinOptions()
            o.Init(t.Bytes, t.Pos)
            assert o.FilterWidth() == o.FilterHeight() and o.StrideW() == o.StrideH() and o.FusedActivationFunction() == 0
            y = new(outs[0], channels[ids[ins[0]]])
            same = 1 if o.Padding() == tflite.Padding.SAME else 0
            ops.append((OP_MAXPOOL2, [ids[ins[0]], y, o.FilterWidth(), o.StrideW(), same], None, None))
        elif name == "CONCATENATION":
            # only in the detector's tail, after the outputs we take
            continue
        else:
            raise ValueError(f"op {name} not supported")

    # graph outputs the converted ops produce directly (e.g. the landmarks) come first
    direct = [ids[g.Outputs(j)] for j in range(g.OutputsLength()) if g.Outputs(j) in ids]
    outputs = direct + [o for o in outputs if o not in direct]
    print(f"{len(ops)} ops, outputs {outputs}: " + ", ".join(
        f"{g.Tensors(t).Name().decode()} {it.get_tensor(t).shape}" for t, i in ids.items() if i in outputs))
    with open(out, "wb") as f:
        f.write(b"YSEG")
        sc = struct.unpack("<i", struct.pack("<f", scale))[0]
        of = struct.unpack("<i", struct.pack("<f", offset))[0]
        f.write(struct.pack("<7i", 1, len(channels), len(ops), len(outputs), 0, sc, of))
        f.write(struct.pack("<%di" % len(outputs), *outputs))
        f.write(struct.pack("<3i", 0, 0, 0))
        for t, a, wgt, b in ops:
            f.write(struct.pack("<2i", t, len(a)))
            f.write(struct.pack("<%di" % len(a), *a))
            if wgt is not None:
                f.write(np.ascontiguousarray(wgt).astype("<f2").tobytes())
            if b is not None:
                f.write(np.ascontiguousarray(b).astype("<f4").tobytes())

    if "--check" in sys.argv:
        # reference: the TFLite tensors we export, NHWC -> CHW, for the same random input
        inv = {v: k for k, v in ids.items()}
        rgb.tofile(out + ".in.rgb")
        refs = [it.get_tensor(inv[o]).reshape(it.get_tensor(inv[o]).shape[1:]).transpose(2, 0, 1) for o in outputs]
        np.savez(out + ".ref.npz", *refs)
        print("saved reference outputs", [r.shape for r in refs])


main()

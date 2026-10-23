"""Writes MediaPipe's face mesh edges (Apache 2.0) as a small binary asset for the app.

Usage: facemesh_edges.py face_mesh_connections.py out.bin
Format (little-endian int16): edge count, edge pairs..., oval point count, oval point indices in loop order,
then the same for each eye and the lips (count, indices), so the app can draw those a little brighter.
"""
import runpy
import struct
import sys

c = runpy.run_path(sys.argv[1])
tess = sorted(c["FACEMESH_TESSELATION"])


def loop(edges):
    adj = {}
    for a, b in edges:
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)
    start = min(adj)
    order, prev, cur = [start], None, start
    while True:
        nxt = [n for n in adj[cur] if n != prev]
        if not nxt or nxt[0] == start:
            break
        prev, cur = cur, nxt[0]
        order.append(cur)
    return order


feature_edges = sorted(set().union(c["FACEMESH_LEFT_EYE"], c["FACEMESH_RIGHT_EYE"], c["FACEMESH_LIPS"],
                                   c["FACEMESH_LEFT_EYEBROW"], c["FACEMESH_RIGHT_EYEBROW"],
                                   c["FACEMESH_LEFT_IRIS"], c["FACEMESH_RIGHT_IRIS"]))
oval = loop(c["FACEMESH_FACE_OVAL"])
with open(sys.argv[2], "wb") as f:
    f.write(struct.pack("<h", len(tess)))
    for a, b in tess:
        f.write(struct.pack("<2h", a, b))
    f.write(struct.pack("<h", len(oval)))
    f.write(struct.pack("<%dh" % len(oval), *oval))
    f.write(struct.pack("<h", len(feature_edges)))
    for a, b in feature_edges:
        f.write(struct.pack("<2h", a, b))
print(len(tess), "edges,", len(oval), "oval points,", len(feature_edges), "feature edges")

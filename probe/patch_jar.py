"""Makes a LiteRT/TFLite Java jar load on Android 4.1.

The jar's classes implement java.lang.AutoCloseable, which only arrived in Android 4.4 (API 19).
Dalvik refuses to load a class whose interface is missing, so this renames the interface to
java.io.Closeable (same close() method, there since API 1). Class files refer to constant pool
entries by index, so a UTF-8 entry can change length safely.

Usage: python3 patch_jar.py in.jar out.jar
"""
import struct
import sys
import zipfile

OLD = b"java/lang/AutoCloseable"
NEW = b"java/io/Closeable"


def patch_class(data):
    count = struct.unpack(">H", data[8:10])[0]
    out = bytearray(data[:10])
    i, n = 10, 1
    while n < count:
        tag = data[i]
        if tag == 1:  # CONSTANT_Utf8
            length = struct.unpack(">H", data[i + 1:i + 3])[0]
            s = data[i + 3:i + 3 + length]
            if s == OLD:
                s = NEW
            out += bytes([1]) + struct.pack(">H", len(s)) + s
            i += 3 + length
        else:
            size = {3: 5, 4: 5, 5: 9, 6: 9, 7: 3, 8: 3, 9: 5, 10: 5, 11: 5, 12: 5,
                    15: 4, 16: 3, 17: 5, 18: 5, 19: 3, 20: 3}[tag]
            out += data[i:i + size]
            i += size
            if tag in (5, 6):  # long and double take two slots
                n += 1
        n += 1
    out += data[i:]
    return bytes(out)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    patched = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename.endswith(".class") and OLD in data:
                data = patch_class(data)
                patched += 1
            zout.writestr(item, data)
    print(f"patched {patched} classes")


main()

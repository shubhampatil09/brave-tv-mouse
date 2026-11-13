"""Make a stock Brave APK installable and launchable on Android TV.

Only the binary AndroidManifest.xml is edited; every other APK entry is copied
byte-for-byte (Brave has resource files whose names differ only by case, so the
APK must never be extracted to a Windows folder).

Changes:
  * <uses-feature android:name="android.software.leanback" android:required="false"/>
  * <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
    (added only if missing)
  * <category android:name="android.intent.category.LEANBACK_LAUNCHER"/> in the
    MAIN/LAUNCHER intent filter

Usage: python patch_tv_manifest.py stock.apk unsigned-out.apk
The output still has to be zipaligned and signed.
"""
import struct
import sys
import zipfile

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_RESOURCE_MAP = 0x0180
ATTR_NAME = 0x01010003
ATTR_REQUIRED = 0x0101028E
TYPE_STRING = 0x03
TYPE_BOOLEAN = 0x12
NONE = 0xFFFFFFFF
UTF8_FLAG = 0x100

LEANBACK = "android.software.leanback"
TOUCHSCREEN = "android.hardware.touchscreen"
LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
LAUNCHER = "android.intent.category.LAUNCHER"


class StringPool:
    def __init__(self, chunk):
        (_, header_size, _, self.count, self.style_count, self.flags,
         self.strings_start, self.styles_start) = struct.unpack_from("<HHIIIIII", chunk, 0)
        if self.style_count:
            raise SystemExit("string pool with styles is not supported")
        self.utf8 = bool(self.flags & UTF8_FLAG)
        self.offsets = list(struct.unpack_from("<%dI" % self.count, chunk, header_size))
        self.data = bytearray(chunk[self.strings_start:])
        self.header_size = header_size
        self.strings = [self._decode(o) for o in self.offsets]

    def _decode(self, off):
        d = self.data
        if self.utf8:
            def length(p):
                n = d[p]
                if n & 0x80:
                    return ((n & 0x7F) << 8) | d[p + 1], p + 2
                return n, p + 1
            _, p = length(off)
            n, p = length(p)
            return bytes(d[p:p + n]).decode("utf-8", "replace")
        n = struct.unpack_from("<H", d, off)[0]
        p = off + 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", d, p)[0]
            p += 2
        return bytes(d[p:p + n * 2]).decode("utf-16-le", "replace")

    def index(self, s):
        """Index of an existing string, or append it (appending never shifts indices)."""
        if s in self.strings:
            return self.strings.index(s)
        if len(s) >= 0x80:
            raise SystemExit("string too long for this patcher")
        if self.utf8:
            b = s.encode("utf-8")
            enc = bytes([len(s), len(b)]) + b + b"\0"
        else:
            enc = struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"
        self.offsets.append(len(self.data))
        self.data += enc
        self.strings.append(s)
        self.count += 1
        return self.count - 1

    def build(self):
        while len(self.data) % 4:
            self.data.append(0)
        strings_start = self.header_size + 4 * self.count
        size = strings_start + len(self.data)
        head = struct.pack("<HHIIIIII", RES_STRING_POOL, self.header_size, size, self.count,
                           0, self.flags, strings_start, 0)
        head += b"\0" * (self.header_size - len(head))
        return head + struct.pack("<%dI" % self.count, *self.offsets) + bytes(self.data)


def chunks(buf, start):
    pos = start
    while pos < len(buf):
        ctype, header_size, size = struct.unpack_from("<HHI", buf, pos)
        yield ctype, header_size, buf[pos:pos + size]
        pos += size


def start_element(line, name, attrs):
    body = struct.pack("<IIIIHHHHHH", line, NONE, NONE, name, 0x14, 0x14, len(attrs), 0, 0, 0)
    for ns, attr_name, raw, dtype, data in attrs:
        body += struct.pack("<IIIHBBI", ns, attr_name, raw, 8, 0, dtype, data)
    return struct.pack("<HHI", RES_XML_START_ELEMENT, 16, 8 + len(body)) + body


def end_element(line, name):
    return struct.pack("<HHIIIII", RES_XML_END_ELEMENT, 16, 24, line, NONE, NONE, name)


def patch_manifest(axml):
    ftype, fheader, _ = struct.unpack_from("<HHI", axml, 0)
    if ftype != RES_XML:
        raise SystemExit("not a binary XML manifest")
    parts = list(chunks(axml, fheader))
    pool = StringPool(parts[0][2])
    if parts[1][0] != RES_XML_RESOURCE_MAP:
        raise SystemExit("resource map not found")
    res_ids = struct.unpack_from("<%dI" % ((len(parts[1][2]) - 8) // 4), parts[1][2], 8)
    name_attr = res_ids.index(ATTR_NAME)
    required_attr = res_ids.index(ATTR_REQUIRED)

    def parse_start(chunk):
        line, _, _, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIIIHHH", chunk, 8)
        attrs = []
        for i in range(attr_count):
            attrs.append(struct.unpack_from("<IIIHBBI", chunk, 16 + attr_start + i * attr_size))
        return line, pool.strings[name], attrs

    def name_value(attrs):
        for ns, attr_name, raw, _, _, dtype, data in attrs:
            if attr_name == name_attr and dtype == TYPE_STRING:
                return pool.strings[data], ns
        return None, None

    # first pass: learn what is there
    android_ns = None
    features = set()
    has_leanback_launcher = False
    for ctype, _, chunk in parts[2:]:
        if ctype != RES_XML_START_ELEMENT:
            continue
        _, tag, attrs = parse_start(chunk)
        value, ns = name_value(attrs)
        if ns is not None and ns != NONE:
            android_ns = ns
        if tag == "uses-feature" and value:
            features.add(value)
        if tag == "category" and value == LEANBACK_LAUNCHER:
            has_leanback_launcher = True
    if android_ns is None:
        raise SystemExit("android namespace not found")

    def feature(line, feature_name):
        idx = pool.index(feature_name)
        tag = pool.index("uses-feature")
        return start_element(line, tag, [
            (android_ns, name_attr, idx, TYPE_STRING, idx),
            (android_ns, required_attr, NONE, TYPE_BOOLEAN, 0),
        ]) + end_element(line, tag)

    out = []
    report = []
    pending_category = False
    done_category = has_leanback_launcher
    done_features = False
    for ctype, _, chunk in parts[2:]:
        if ctype == RES_XML_START_ELEMENT:
            line, tag, attrs = parse_start(chunk)
            if tag == "application" and not done_features:
                for f in (LEANBACK, TOUCHSCREEN):
                    if f not in features:
                        out.append(feature(line, f))
                        report.append("added uses-feature " + f + " required=false")
                done_features = True
            if tag == "category" and not done_category and name_value(attrs)[0] == LAUNCHER:
                pending_category = True
        out.append(chunk)
        if ctype == RES_XML_END_ELEMENT and pending_category:
            line = struct.unpack_from("<I", chunk, 8)[0]
            idx = pool.index(LEANBACK_LAUNCHER)
            tag = pool.index("category")
            out.append(start_element(line, tag, [(android_ns, name_attr, idx, TYPE_STRING, idx)])
                       + end_element(line, tag))
            report.append("added category LEANBACK_LAUNCHER after LAUNCHER")
            pending_category = False
            done_category = True
    if not done_category:
        raise SystemExit("no LAUNCHER category found to extend")

    body = pool.build() + parts[1][2] + b"".join(out)
    return struct.pack("<HHI", RES_XML, 8, 8 + len(body)) + body, report


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename.startswith("META-INF/") and info.filename.rsplit(".", 1)[-1] in (
                    "SF", "RSA", "DSA", "EC", "MF"):
                continue  # old signature
            if info.filename == "AndroidManifest.xml":
                data, report = patch_manifest(data)
                for line in report or ["manifest already had the TV entries"]:
                    print(line)
            out = zipfile.ZipInfo(info.filename, info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            zout.writestr(out, data)
    print("wrote", dst)


if __name__ == "__main__":
    main()

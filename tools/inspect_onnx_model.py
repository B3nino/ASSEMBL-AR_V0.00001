"""Read ONNX model IO/metadata without importing an inference runtime or loading weights.

Protobuf wire fields are decoded only for model metadata and tensor shapes. This is
an inspection utility, not a model validator or replacement for native replay.
"""
import argparse
import hashlib
import json
from pathlib import Path


def varint(data, pos):
    value = 0
    for shift in range(0, 70, 7):
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise ValueError("Oversized protobuf varint")


def fields(data):
    pos = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        wire, number = tag & 7, tag >> 3
        if wire == 0:
            value, pos = varint(data, pos)
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value, pos = data[pos:pos+size], pos+size
        elif wire == 2:
            size, pos = varint(data, pos)
            value, pos = data[pos:pos+size], pos+size
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        if pos > len(data):
            raise ValueError("Truncated protobuf field")
        yield number, value


def string(data):
    return bytes(data).decode("utf-8", errors="replace")


def value_info(data):
    out = {}
    for n, v in fields(data):
        if n == 1:
            out["name"] = string(v)
        elif n == 2:
            for tn, tv in fields(v):
                if tn == 1:
                    for en, ev in fields(tv):
                        if en == 1:
                            out["element_type"] = ev
                        elif en == 2:
                            out["shape"] = [next((dv if dn == 1 else string(dv) for dn, dv in fields(dim) if dn in (1, 2)), None) for sn, dim in fields(ev) if sn == 1]
    return out


def inspect(path):
    data = memoryview(path.read_bytes())
    result = {"file": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "metadata": {}, "inputs": [], "outputs": [], "operators": {}}
    for n, v in fields(data):
        if n == 1:
            result["ir_version"] = v
        elif n in (2, 3):
            result["producer" if n == 2 else "producer_version"] = string(v)
        elif n == 7:
            for gn, gv in fields(v):
                if gn in (11, 12):
                    result["inputs" if gn == 11 else "outputs"].append(value_info(gv))
                elif gn == 1:
                    op = next((string(nv) for nn, nv in fields(gv) if nn == 4), "unknown")
                    result["operators"][op] = result["operators"].get(op, 0) + 1
        elif n == 8:
            result.setdefault("opsets", []).append({str(k): string(x) if k == 1 else x for k, x in fields(v)})
        elif n == 14:
            values = {k: string(x) for k, x in fields(v)}
            result["metadata"][values.get(1, "")] = values.get(2, "")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.model), indent=2))

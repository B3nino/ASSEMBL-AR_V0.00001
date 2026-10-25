"""Offline diagnostics read the production Lowe/Hamming constants directly.

This module does not train or change the Android app. Native replay remains
responsible for validating the entire production geometry pipeline.
"""
from pathlib import Path
import math
import re

_SOURCE = (Path(__file__).resolve().parents[1] /
           "app/src/main/java/dev/chyken/assembl_ar/vision/OrbMatchPolicy.java")
_text = _SOURCE.read_text(encoding="utf-8")
_ratio = re.search(r"LOWE_RATIO\s*=\s*([0-9.]+)\s*;", _text)
_limit = re.search(r"MAX_HAMMING\s*=\s*(\d+)\s*;", _text)
if _ratio is None or _limit is None:
    raise RuntimeError("Review the production ORB policy before using offline diagnostics")
LOWE_RATIO = float(_ratio.group(1))
MAX_HAMMING = int(_limit.group(1))


def accepts(first, second):
    return (math.isfinite(first) and math.isfinite(second) and first >= 0
            and second > 0 and first <= MAX_HAMMING and first < LOWE_RATIO * second)


if __name__ == "__main__":
    cases = [(64,100,True),(65,100,False),(75,100,False),(3,4,False),
             (2,4,True),(0,0,False),(-1,5,False),(math.nan,5,False)]
    for first,second,expected in cases:
        assert accepts(first,second) == expected, (first,second)
    print(f"ORB policy: {LOWE_RATIO}/{MAX_HAMMING}; {len(cases)} boundary checks passed")

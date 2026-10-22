"""Host equivalent of ReferenceDescriptorPyramid; validation only, not a device benchmark."""
import cv2 as cv
import numpy as np


def reference_pyramid(image, orb, mask, inverted, extract, boundary=None):
    """Retain source sampling, add camera scales, and restore source-coordinate keypoints."""
    points, descriptors = [], []
    height, width = image.shape[:2]
    largest = max(width, height)
    for extent in sorted(set((min(512, largest), min(768, largest), largest))):
        scale = extent / largest
        level = cv.resize(image, (round(width * scale), round(height * scale)), interpolation=cv.INTER_AREA)
        region = None if mask is None else cv.resize(mask, (level.shape[1], level.shape[0]), interpolation=cv.INTER_NEAREST)
        if boundary is not None and extent < largest:
            normalized = cv.createCLAHE(2.0, (8, 8)).apply(cv.cvtColor(level, cv.COLOR_BGR2GRAY))
            if inverted: normalized = cv.bitwise_not(normalized)
            native, rows = orb.detectAndCompute(normalized, region)
            keys = np.array([k.pt for k in native], np.float32)
            if rows is not None:
                source = keys * np.array([width / level.shape[1], height / level.shape[0]], np.float32)
                radius = np.array([k.size for k in native]) * np.sqrt(width / level.shape[1] * height / level.shape[0]) * .71
                l, t, r, b = boundary; x, y = source[:, 0], source[:, 1]
                inside = (x >= l) & (x <= r) & (y >= t) & (y <= b)
                safe = np.where(inside, (x-radius >= l) & (x+radius <= r) & (y-radius >= t) & (y+radius <= b),
                                (x+radius < l) | (x-radius > r) | (y+radius < t) | (y-radius > b))
                keys, rows = keys[safe], rows[safe]
        else:
            keys, rows = extract(level, orb, region, inverted)
        if rows is not None:
            points.append(keys * np.array([width / level.shape[1], height / level.shape[0]], np.float32))
            descriptors.append(rows)
    return (np.vstack(points), np.vstack(descriptors)) if descriptors else (np.empty((0, 2), np.float32), None)

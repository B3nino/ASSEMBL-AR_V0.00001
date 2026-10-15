package dev.chyken.assembl_ar.render;

import android.content.Context;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OBJ subset: v, vt, convex polygon f, mtllib, usemtl; MTL newmtl, Kd, d and plain
 * map_Kd paths. Positive/relative indices and v/vt/vn faces are supported. Normals
 * are rebuilt per triangle; smoothing, PBR and GLB are not supported. Standard
 * bottom-origin OBJ V is flipped once for Android's top-row-first bitmap upload.
 */
final class ObjModel {
    interface AssetSource { InputStream open(String path) throws IOException; }

    static final class Material {
        final String name;
        final float[] color;
        @Nullable final String texturePath;
        Material(String name, float[] color, @Nullable String texturePath) {
            this.name = name; this.color = color.clone(); this.texturePath = texturePath;
        }
    }

    static final class Batch {
        final int firstVertex;
        final int vertexCount;
        final Material material;
        final boolean hasTextureCoordinates;
        Batch(int firstVertex, int vertexCount, Material material, boolean hasTextureCoordinates) {
            this.firstVertex = firstVertex; this.vertexCount = vertexCount;
            this.material = material; this.hasTextureCoordinates = hasTextureCoordinates;
        }
    }

    private static final Material DEFAULT = new Material("", new float[]{1f, 1f, 1f, 1f}, null);
    private final FloatBuffer vertices;
    private final FloatBuffer normals;
    private final FloatBuffer textureCoordinates;
    private final int vertexCount;
    private final List<Batch> batches;
    private final float[] halfExtents;
    private final float[] boundsMin = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
    private final float[] boundsMax = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

    private ObjModel(float[] positions, float[] uvs, List<Batch> batches) {
        vertices = buffer(positions); normals = buffer(flatNormals(positions));
        textureCoordinates = buffer(uvs); vertexCount = positions.length / 3;
        this.batches = Collections.unmodifiableList(batches);
        halfExtents = new float[3];
        for (int i = 0; i < positions.length; i++) {
            int axis = i % 3;
            // Keep origin-relative extents used by AR placement; previews also need true bounds.
            halfExtents[axis] = Math.max(halfExtents[axis], Math.abs(positions[i]));
            boundsMin[axis] = Math.min(boundsMin[axis], positions[i]);
            boundsMax[axis] = Math.max(boundsMax[axis], positions[i]);
        }
    }

    FloatBuffer vertices() { vertices.position(0); return vertices; }
    FloatBuffer normals() { normals.position(0); return normals; }
    FloatBuffer textureCoordinates() { textureCoordinates.position(0); return textureCoordinates; }
    int vertexCount() { return vertexCount; }
    List<Batch> batches() { return batches; }
    float halfExtent(int axis) { return halfExtents[axis]; }
    float center(int axis) { return boundsMin[axis] * 0.5f + boundsMax[axis] * 0.5f; }

    /** Radius of the centered bounding box's enclosing sphere, independent of authored units. */
    float boundingRadius() {
        double sum = 0;
        for (int axis = 0; axis < 3; axis++) {
            double halfSize = ((double) boundsMax[axis] - boundsMin[axis]) * 0.5;
            sum += halfSize * halfSize;
        }
        return (float) Math.sqrt(sum);
    }

    @Nullable static ObjModel load(Context context, String assetPath) {
        return load(path -> context.getAssets().open(path), assetPath);
    }

    @Nullable static ObjModel load(AssetSource assets, String assetPath) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<Float> triangles = new ArrayList<>();
        List<Float> expandedUvs = new ArrayList<>();
        List<PendingBatch> pending = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        String selectedMaterial = "";
        try (BufferedReader reader = reader(assets, assetPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = clean(line);
                if (trimmed.isEmpty()) continue;
                String[] values = trimmed.split("\\s+");
                switch (values[0]) {
                    case "v":
                        if (values.length < 4) return null;
                        positions.add(new float[]{number(values[1]), number(values[2]), number(values[3])});
                        break;
                    case "vt":
                        if (values.length < 3) return null;
                        uvs.add(new float[]{number(values[1]), 1f - number(values[2])});
                        break;
                    case "mtllib":
                        for (int i = 1; i < values.length; i++) {
                            String path = relativePath(assetPath, values[i]);
                            if (path != null) readMaterials(assets, path, materials);
                        }
                        break;
                    case "usemtl":
                        selectedMaterial = trimmed.substring(6).trim();
                        break;
                    case "f":
                        if (values.length < 4) return null;
                        FaceVertex first = faceVertex(values[1], positions.size(), uvs.size());
                        for (int i = 2; i < values.length - 1; i++) {
                            FaceVertex second = faceVertex(values[i], positions.size(), uvs.size());
                            FaceVertex third = faceVertex(values[i + 1], positions.size(), uvs.size());
                            boolean textured = first.uv >= 0 && second.uv >= 0 && third.uv >= 0;
                            int start = triangles.size() / 3;
                            for (FaceVertex vertex : new FaceVertex[]{first, second, third}) {
                                append(triangles, positions.get(vertex.position));
                                if (textured) append(expandedUvs, uvs.get(vertex.uv));
                                else { expandedUvs.add(0f); expandedUvs.add(0f); }
                            }
                            PendingBatch previous = pending.isEmpty() ? null : pending.get(pending.size() - 1);
                            if (previous != null && previous.material.equals(selectedMaterial)
                                    && previous.textured == textured) previous.count += 3;
                            else pending.add(new PendingBatch(start, selectedMaterial, textured));
                        }
                        break;
                    default: break;
                }
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return null;
        }
        if (triangles.isEmpty()) return null;
        List<Batch> batches = new ArrayList<>();
        for (PendingBatch batch : pending) {
            Material material = materials.get(batch.material);
            batches.add(new Batch(batch.first, batch.count,
                    material == null ? DEFAULT : material, batch.textured));
        }
        return new ObjModel(array(triangles), array(expandedUvs), batches);
    }

    private static void readMaterials(AssetSource assets, String path, Map<String, Material> result) {
        String name = null;
        float[] color = {1f, 1f, 1f, 1f};
        String texture = null;
        try (BufferedReader reader = reader(assets, path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = clean(line);
                if (trimmed.isEmpty()) continue;
                String[] values = trimmed.split("\\s+");
                switch (values[0]) {
                    case "newmtl":
                        if (name != null) result.put(name, new Material(name, color, texture));
                        name = trimmed.substring(6).trim();
                        color = new float[]{1f, 1f, 1f, 1f}; texture = null;
                        break;
                    case "Kd":
                        if (values.length >= 4)
                            for (int i = 0; i < 3; i++) color[i] = unit(number(values[i + 1]));
                        break;
                    case "d":
                        if (values.length >= 2) color[3] = unit(number(values[1]));
                        break;
                    case "map_Kd":
                        String rawPath = trimmed.substring(6).trim();
                        // Option-bearing map_Kd is outside the supported import subset.
                        texture = rawPath.startsWith("-") ? null : relativePath(path, rawPath);
                        break;
                    default: break;
                }
            }
            if (name != null) result.put(name, new Material(name, color, texture));
        } catch (IOException | IllegalArgumentException ignored) {
            // Keep useful geometry even when its optional material library is unavailable.
        }
    }

    @Nullable static String relativePath(String source, String relative) {
        if (relative.isEmpty() || relative.startsWith("/") || relative.startsWith("\\")
                || relative.contains(":")) return null;
        int slash = source.lastIndexOf('/');
        String combined = (slash >= 0 ? source.substring(0, slash + 1) : "") + relative.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else parts.add(part);
        }
        return String.join("/", parts);
    }

    private static BufferedReader reader(AssetSource source, String path) throws IOException {
        return new BufferedReader(new InputStreamReader(source.open(path), StandardCharsets.UTF_8));
    }
    private static String clean(String line) {
        int comment = line.indexOf('#');
        return (comment >= 0 ? line.substring(0, comment) : line).trim();
    }
    private static float number(String value) {
        float parsed = Float.parseFloat(value);
        if (!Float.isFinite(parsed)) throw new IllegalArgumentException("Non-finite coordinate");
        return parsed;
    }
    private static float unit(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static FaceVertex faceVertex(String token, int positions, int uvs) {
        String[] indices = token.split("/", -1);
        int position = index(indices[0], positions);
        int uv = indices.length > 1 && !indices[1].isEmpty() ? index(indices[1], uvs) : -1;
        return new FaceVertex(position, uv);
    }
    private static int index(String raw, int size) {
        int parsed = Integer.parseInt(raw);
        int result = parsed < 0 ? size + parsed : parsed - 1;
        if (result < 0 || result >= size) throw new IndexOutOfBoundsException("Invalid OBJ index");
        return result;
    }
    private static void append(List<Float> output, float[] values) {
        for (float value : values) output.add(value);
    }
    private static float[] array(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }
    private static FloatBuffer buffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0); return result;
    }
    private static float[] flatNormals(float[] vertices) {
        float[] result = new float[vertices.length];
        for (int offset = 0; offset + 8 < vertices.length; offset += 9) {
            float abX = vertices[offset + 3] - vertices[offset];
            float abY = vertices[offset + 4] - vertices[offset + 1];
            float abZ = vertices[offset + 5] - vertices[offset + 2];
            float acX = vertices[offset + 6] - vertices[offset];
            float acY = vertices[offset + 7] - vertices[offset + 1];
            float acZ = vertices[offset + 8] - vertices[offset + 2];
            float x = abY * acZ - abZ * acY;
            float y = abZ * acX - abX * acZ;
            float z = abX * acY - abY * acX;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > 0.000001f) { x /= length; y /= length; z /= length; }
            else { x = 0f; y = 1f; z = 0f; }
            for (int vertex = 0; vertex < 3; vertex++) {
                int target = offset + vertex * 3;
                result[target] = x; result[target + 1] = y; result[target + 2] = z;
            }
        }
        return result;
    }
    private static final class FaceVertex {
        final int position, uv;
        FaceVertex(int position, int uv) { this.position = position; this.uv = uv; }
    }
    private static final class PendingBatch {
        final int first;
        final String material;
        final boolean textured;
        int count = 3;
        PendingBatch(int first, String material, boolean textured) {
            this.first = first; this.material = material; this.textured = textured;
        }
    }
}

package dev.chyken.assembl_ar.model;

import java.util.Arrays;
import java.util.Objects;

public final class OrbDescriptorData {
    private final String id;
    private final String referenceImageId;
    private final String algorithmVersion;
    private final String parametersJson;
    private final int descriptorRows;
    private final int descriptorColumns;
    private final int openCvType;
    private final int featureCount;
    private final byte[] keypointsBlob;
    private final byte[] descriptorsBlob;

    public OrbDescriptorData(
            String id,
            String referenceImageId,
            String algorithmVersion,
            String parametersJson,
            int descriptorRows,
            int descriptorColumns,
            int openCvType,
            int featureCount,
            byte[] keypointsBlob,
            byte[] descriptorsBlob) {
        this.id = Objects.requireNonNull(id, "id");
        this.referenceImageId = Objects.requireNonNull(referenceImageId, "referenceImageId");
        this.algorithmVersion = Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        this.parametersJson = Objects.requireNonNull(parametersJson, "parametersJson");
        this.descriptorRows = descriptorRows;
        this.descriptorColumns = descriptorColumns;
        this.openCvType = openCvType;
        this.featureCount = featureCount;
        this.keypointsBlob = keypointsBlob == null ? new byte[0] : Arrays.copyOf(keypointsBlob, keypointsBlob.length);
        this.descriptorsBlob = descriptorsBlob == null ? new byte[0] : Arrays.copyOf(descriptorsBlob, descriptorsBlob.length);
    }

    public String getId() { return id; }
    public String getReferenceImageId() { return referenceImageId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getParametersJson() { return parametersJson; }
    public int getDescriptorRows() { return descriptorRows; }
    public int getDescriptorColumns() { return descriptorColumns; }
    public int getOpenCvType() { return openCvType; }
    public int getFeatureCount() { return featureCount; }
    public byte[] getKeypointsBlob() { return Arrays.copyOf(keypointsBlob, keypointsBlob.length); }
    public byte[] getDescriptorsBlob() { return Arrays.copyOf(descriptorsBlob, descriptorsBlob.length); }
}

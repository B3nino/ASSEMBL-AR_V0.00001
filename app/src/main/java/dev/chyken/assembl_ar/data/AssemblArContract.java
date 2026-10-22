package dev.chyken.assembl_ar.data;

/** SQLite schema shared by the helper and repository. */
final class AssemblArContract {
    static final String DATABASE_NAME = "assembl_ar.db";
    static final int DATABASE_VERSION = 6;

    private AssemblArContract() {
    }

    static final class Projects {
        static final String TABLE = "projects";
        static final String ID = "id";
        static final String TITLE = "title";
        static final String KIT_NAME = "kit_name";
        static final String DESCRIPTION = "description";
        static final String MANUAL_DOCUMENT_ID = "manual_document_id";
        static final String MANUAL_DOCUMENT_TITLE = "manual_document_title";
        static final String PAGE_START = "page_start";
        static final String PAGE_END = "page_end";
        static final String ASSET_PATHS = "asset_paths";
    }

    static final class Components {
        static final String TABLE = "components";
        static final String ID = "id";
        static final String NAME = "name";
        static final String CATEGORY = "category";
        static final String KIT_QUANTITY = "kit_quantity";
        static final String DESCRIPTION = "description";
        static final String PURPOSE = "purpose";
        static final String ASSEMBLY_USAGE = "assembly_usage";
        static final String RECOGNITION_NOTES = "recognition_notes";
        static final String MANUAL_DOCUMENT_ID = "manual_document_id";
        static final String MANUAL_DOCUMENT_TITLE = "manual_document_title";
        static final String PAGE_START = "page_start";
        static final String PAGE_END = "page_end";
        static final String ASSET_PATHS = "asset_paths";
    }

    static final class Steps {
        static final String TABLE = "assembly_steps";
        static final String ID = "id";
        static final String PROJECT_ID = "project_id";
        static final String STEP_NUMBER = "step_number";
        static final String TITLE = "title";
        static final String SUMMARY = "summary";
        static final String VALIDATION_TYPE = "validation_type";
        static final String VALIDATION_PROMPT = "validation_prompt";
        static final String MANUAL_DOCUMENT_ID = "manual_document_id";
        static final String MANUAL_DOCUMENT_TITLE = "manual_document_title";
        static final String PAGE_START = "page_start";
        static final String PAGE_END = "page_end";
        static final String ASSET_PATHS = "asset_paths";
    }

    static final class Instructions {
        static final String TABLE = "step_instructions";
        static final String STEP_ID = "step_id";
        static final String ORDER_INDEX = "order_index";
        static final String TYPE = "instruction_type";
        static final String TEXT = "instruction_text";
        static final String MANUAL_PAGE = "manual_page";
        static final String REFERENCE_IMAGE_PATH = "reference_image_path";
    }

    static final class Requirements {
        static final String TABLE = "step_requirements";
        static final String STEP_ID = "step_id";
        static final String COMPONENT_ID = "component_id";
        static final String QUANTITY = "quantity";
        static final String NOTES = "notes";
    }

    static final class Connections {
        static final String TABLE = "pin_connections";
        static final String STEP_ID = "step_id";
        static final String ORDER_INDEX = "order_index";
        static final String SOURCE_COMPONENT_ID = "source_component_id";
        static final String SOURCE_PIN = "source_pin";
        static final String TARGET_COMPONENT_ID = "target_component_id";
        static final String TARGET_PIN = "target_pin";
        static final String WIRE_COLOR = "wire_color";
        static final String SIGNAL = "signal";
        static final String NOTES = "notes";
    }

    static final class StepOverlays {
        static final String TABLE = "step_overlays";
        static final String STEP_ID = "step_id";
        static final String ORDER_INDEX = "order_index";
        static final String OVERLAY_TYPE = "overlay_type";
    }

    static final class Progress {
        static final String TABLE = "step_progress";
        static final String PROJECT_ID = "project_id";
        static final String STEP_ID = "step_id";
        static final String STATUS = "status";
        static final String VALIDATION_CONFIRMED = "validation_confirmed";
        static final String UPDATED_AT = "updated_at";
    }

    static final class LearningModules {
        static final String TABLE = "learning_modules";
        static final String ID = "id";
        static final String ORDER_INDEX = "order_index";
        static final String TITLE = "title";
        static final String OVERVIEW = "overview";
        static final String PREREQUISITE_MODULE_ID = "prerequisite_module_id";
        static final String MANUAL_DOCUMENT_ID = "manual_document_id";
        static final String MANUAL_DOCUMENT_TITLE = "manual_document_title";
        static final String PAGE_START = "page_start";
        static final String PAGE_END = "page_end";
        static final String ASSET_PATHS = "asset_paths";
    }

    static final class LearningSteps {
        static final String TABLE = "learning_steps";
        static final String ID = "id";
        static final String MODULE_ID = "module_id";
        static final String ORDER_INDEX = "order_index";
        static final String TITLE = "title";
        static final String CONTENT_TYPE = "content_type";
        static final String CONTENT = "content";
        static final String CODE_SNIPPET = "code_snippet";
        static final String WARNING = "warning";
        static final String DEPENDENCY = "dependency";
        static final String MANUAL_DOCUMENT_ID = "manual_document_id";
        static final String MANUAL_DOCUMENT_TITLE = "manual_document_title";
        static final String PAGE_START = "page_start";
        static final String PAGE_END = "page_end";
        static final String ASSET_PATHS = "asset_paths";
    }

    static final class Registrations {
        static final String TABLE = "component_registrations";
        static final String ID = "id";
        static final String COMPONENT_ID = "component_id";
        static final String ADMINISTRATOR_ID = "administrator_id";
        static final String DISPLAY_NAME = "display_name";
        static final String DESCRIPTION = "description";
        static final String PURPOSE = "purpose";
        static final String ASSEMBLY_USAGE = "assembly_usage";
        static final String MANUAL_REFERENCE = "manual_reference";
        static final String ACTIVE = "active";
        static final String CREATED_AT = "created_at";
        static final String UPDATED_AT = "updated_at";
    }

    static final class ReferenceImages {
        static final String TABLE = "component_reference_images";
        static final String ID = "id";
        static final String REGISTRATION_ID = "registration_id";
        static final String COMPONENT_ID = "component_id";
        static final String IMAGE_PATH = "image_path";
        static final String ANGLE_LABEL = "angle_label";
        static final String AZIMUTH_DEGREES = "azimuth_degrees";
        static final String ELEVATION_DEGREES = "elevation_degrees";
        static final String DISTANCE_MILLIMETERS = "distance_millimeters";
        static final String LIGHTING_CONDITION = "lighting_condition";
        static final String IS_SYNTHETIC = "is_synthetic";
        static final String PIXEL_WIDTH = "pixel_width";
        static final String PIXEL_HEIGHT = "pixel_height";
        static final String SHA256 = "sha256";
        static final String CAPTURED_AT = "captured_at";
    }

    static final class OrbDescriptors {
        static final String TABLE = "orb_descriptors";
        static final String ID = "id";
        static final String REFERENCE_IMAGE_ID = "reference_image_id";
        static final String ALGORITHM_VERSION = "algorithm_version";
        static final String PARAMETERS_JSON = "parameters_json";
        static final String DESCRIPTOR_ROWS = "descriptor_rows";
        static final String DESCRIPTOR_COLUMNS = "descriptor_columns";
        static final String OPENCV_TYPE = "opencv_type";
        static final String FEATURE_COUNT = "feature_count";
        static final String KEYPOINTS_BLOB = "keypoints_blob";
        static final String DESCRIPTORS_BLOB = "descriptors_blob";
    }

    static final class Spatial {
        static final String TABLE = "component_spatial_metadata";
        static final String COMPONENT_ID = "component_id";
        static final String WIDTH_MILLIMETERS = "width_millimeters";
        static final String HEIGHT_MILLIMETERS = "height_millimeters";
        static final String DEPTH_MILLIMETERS = "depth_millimeters";
        static final String COORDINATE_FRAME = "coordinate_frame";
        static final String ANCHOR_POINTS_JSON = "anchor_points_json";
        static final String ORIENTATION_RULES_JSON = "orientation_rules_json";
        static final String OBJECT_POINTS_BLOB = "object_points_blob";
        static final String MODEL_ASSET_PATH = "model_asset_path";
        static final String UPDATED_AT = "updated_at";
    }
}

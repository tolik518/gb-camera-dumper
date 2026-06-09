package fyi.r0.gbxcam;

final class NativeGbcam {
    interface Progress {
        void onProgress(String message);
    }

    static {
        System.loadLibrary("gbxcam_ffi");
    }

    private NativeGbcam() {
    }

    static native String version();

    static native int defaultPaletteIndex();

    static native String paletteLabels();

    static native String paletteColors();

    static native String loadGalleryFromFd(
            int fd,
            int interfaceNumber,
            int epOut,
            int epIn,
            boolean initializeCh340,
            String outputDir,
            int paletteIndex,
            Progress progress);

    static native String deletePhotosFromFd(
            int fd,
            int interfaceNumber,
            int epOut,
            int epIn,
            boolean initializeCh340,
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            int paletteIndex,
            Progress progress);

    static native String recoverPhotosFromFd(
            int fd,
            int interfaceNumber,
            int epOut,
            int epIn,
            boolean initializeCh340,
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            int paletteIndex,
            Progress progress);

    static native String recoverPhotosFromSave(
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            int paletteIndex,
            Progress progress);

    static native String reorderPhotosFromFd(
            int fd,
            int interfaceNumber,
            int epOut,
            int epIn,
            boolean initializeCh340,
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            int paletteIndex,
            Progress progress);

    static native String loadGalleryFromSave(String savePath, String outputDir, int paletteIndex);

    static native String mergeRgbFromSave(
            String savePath,
            String outputPath,
            String physicalSlotsCsv,
            String order,
            String algorithm);

    static native String detectRgbMergesFromSave(
            String savePath,
            String order4,
            String order3,
            String defaultAlgorithm,
            String algorithmOverridesJson);
}

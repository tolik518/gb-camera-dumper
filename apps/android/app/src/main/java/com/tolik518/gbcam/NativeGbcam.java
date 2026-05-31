package com.tolik518.gbcam;

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

    static native String loadGalleryFromFd(int fd, String outputDir, int paletteIndex, Progress progress);

    static native String deletePhotosFromFd(
            int fd,
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            int paletteIndex,
            Progress progress);

    static native String loadGalleryFromSave(String savePath, String outputDir, int paletteIndex);

    static native String dumpFromFd(int fd, String outputDir, boolean eraseAfter);
}

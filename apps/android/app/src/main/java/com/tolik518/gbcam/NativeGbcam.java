package com.tolik518.gbcam;

final class NativeGbcam {
    interface Progress {
        void onProgress(String message);
    }

    static {
        System.loadLibrary("gbcam_ffi");
    }

    private NativeGbcam() {
    }

    static native String version();

    static native String loadGalleryFromFd(int fd, String outputDir, Progress progress);

    static native String deletePhotosFromFd(
            int fd,
            String savePath,
            String outputDir,
            String physicalSlotsCsv,
            Progress progress);

    static native String dumpFromFd(int fd, String outputDir, boolean eraseAfter);
}

package fyi.r0.gbxcam;

/** Minimal supplier type for app-local lambdas without relying on API 24 java.util.function. */
interface AppSupplier<T> {
    T get();
}

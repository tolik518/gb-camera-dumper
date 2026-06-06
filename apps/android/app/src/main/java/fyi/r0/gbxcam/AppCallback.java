package fyi.r0.gbxcam;

/** Minimal callback type for app-local lambdas without relying on API 24 java.util.function. */
interface AppCallback<T> {
    void accept(T value);
}

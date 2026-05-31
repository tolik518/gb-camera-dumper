set shell := ["bash", "--noprofile", "--norc", "-cu"]
set tempdir := "/tmp"

android-api := env_var_or_default("ANDROID_API", "23")
android-target := "aarch64-linux-android"
android-host := "linux-x86_64"
android-sdk-api := "35"
gradle-version := "8.10.2"
cmdline-tools-version := "14742923"

default:
    just --list

check:
    cargo fmt --all --check
    cargo test --workspace

extract-sav save="dump/GAMEBOYCAMERA.sav" output="extracted":
    #!/usr/bin/env bash
    set -euo pipefail
    repo="$PWD"
    save_path="$repo/{{save}}"
    output_path="$repo/{{output}}"
    test -f "$save_path" || { echo "Save file not found: $save_path" >&2; exit 1; }
    mkdir -p "$output_path"
    cargo build -q -p gbxcam-cli --bin gbxcam
    cd "$output_path"
    "$repo/target/debug/gbxcam" "$save_path"
    echo "Extracted to: $output_path"

_android-ndk-home api=android-api:
    #!/usr/bin/env bash
    set -euo pipefail
    linker_rel="toolchains/llvm/prebuilt/{{android-host}}/bin/aarch64-linux-android{{api}}-clang"

    check_ndk() {
    local ndk="$1"
    if [[ -n "$ndk" && -x "$ndk/$linker_rel" ]]; then
    printf '%s\n' "$ndk"
    return 0
    fi
    return 1
    }

    if check_ndk "${ANDROID_NDK_HOME:-}"; then exit 0; fi
    if check_ndk "${ANDROID_NDK_ROOT:-}"; then exit 0; fi

    shopt -s nullglob
    candidates=( "${ANDROID_HOME:-}"/ndk/* "${ANDROID_SDK_ROOT:-}"/ndk/* "$HOME"/Android/Sdk/ndk/* "$HOME"/android-sdk/ndk/* /usr/lib/android-sdk/ndk/* /opt/android-sdk/ndk/* /usr/lib/android-ndk* /opt/android-ndk* /usr/local/lib/android-ndk* /usr/local/android-ndk* )

    for ndk in "${candidates[@]}"; do
    if check_ndk "$ndk"; then exit 0; fi
    done

    while IFS= read -r clang; do
    ndk="${clang%/$linker_rel}"
    if check_ndk "$ndk"; then exit 0; fi
    done < <(find /usr/lib /opt "$HOME/Android/Sdk" "$HOME/android-sdk" -type f -path "*/$linker_rel" 2>/dev/null || true)

    echo "Could not find Android NDK linker: $linker_rel" >&2
    echo "Install an NDK, or set ANDROID_NDK_HOME=/path/to/android-ndk." >&2
    exit 1

android-ndk-home api=android-api:
    @just --quiet _android-ndk-home {{api}}

_android-sdk-home:
    #!/usr/bin/env bash
    set -euo pipefail
    check_sdk() {
    local sdk="$1"
    if [[ -n "$sdk" && -f "$sdk/platforms/android-{{android-sdk-api}}/android.jar" ]]; then
    printf '%s\n' "$sdk"
    return 0
    fi
    return 1
    }

    if check_sdk "${ANDROID_HOME:-}"; then exit 0; fi
    if check_sdk "${ANDROID_SDK_ROOT:-}"; then exit 0; fi

    candidates=( "$PWD"/.android-sdk "$HOME"/Android/Sdk "$HOME"/android-sdk /usr/lib/android-sdk /opt/android-sdk )
    for sdk in "${candidates[@]}"; do
    if check_sdk "$sdk"; then exit 0; fi
    done

    echo "Could not find Android SDK platform android-{{android-sdk-api}}." >&2
    echo "Install Android SDK command-line tools, then run:" >&2
    echo "  sdkmanager 'platforms;android-{{android-sdk-api}}'" >&2
    echo "Or let this justfile install a local SDK with:" >&2
    echo "  just android-sdk-platform" >&2
    echo "Or set ANDROID_HOME to an SDK that already has platforms/android-{{android-sdk-api}}." >&2
    exit 1

android-sdk-home:
    @just --quiet _android-sdk-home

_android-sdkmanager:
    #!/usr/bin/env bash
    set -euo pipefail
    if command -v sdkmanager >/dev/null; then
    command -v sdkmanager
    exit 0
    fi

    candidates=( "$PWD"/.android-sdk "$HOME"/Android/Sdk "$HOME"/android-sdk /usr/lib/android-sdk /opt/android-sdk )
    for sdk in "${candidates[@]}"; do
    manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
    if [[ -x "$manager" ]]; then
    printf '%s\n' "$manager"
    exit 0
    fi
    done

    echo "Could not find sdkmanager. Run: just android-sdk-bootstrap" >&2
    exit 1

android-sdkmanager:
    @just --quiet _android-sdkmanager

android-sdk-bootstrap:
    #!/usr/bin/env bash
    set -euo pipefail
    sdk="$PWD/.android-sdk"
    manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
    if [[ -x "$manager" ]]; then
    echo "Found: $manager"
    exit 0
    fi

    mkdir -p "$sdk" "$sdk/.downloads"
    zip="$sdk/.downloads/commandlinetools-linux-{{cmdline-tools-version}}_latest.zip"
    if [[ ! -f "$zip" ]]; then
    curl -L "https://dl.google.com/android/repository/commandlinetools-linux-{{cmdline-tools-version}}_latest.zip" -o "$zip"
    fi
    rm -rf "$sdk/cmdline-tools"
    unzip -q "$zip" -d "$sdk"
    mkdir -p "$sdk/cmdline-tools/latest"
    find "$sdk/cmdline-tools" -mindepth 1 -maxdepth 1 ! -name latest -exec mv {} "$sdk/cmdline-tools/latest/" \;
    echo "Installed: $manager"

android-sdk-platform api=android-sdk-api: android-sdk-bootstrap
    #!/usr/bin/env bash
    set -euo pipefail
    sdk="$PWD/.android-sdk"
    manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
    (yes || true) | "$manager" --sdk_root="$sdk" --licenses >/dev/null
    "$manager" --sdk_root="$sdk" "platforms;android-{{api}}" "build-tools;35.0.0" "platform-tools"
    echo "Installed Android SDK platform android-{{api}} in $sdk"

_gradle-bin:
    #!/usr/bin/env bash
    set -euo pipefail
    if command -v gradle >/dev/null; then
    command -v gradle
    exit 0
    fi

    gradle_dir=".gradle-local/gradle-{{gradle-version}}"
    gradle_bin="$gradle_dir/bin/gradle"
    if [[ ! -x "$gradle_bin" ]]; then
    mkdir -p .gradle-local
    zip=".gradle-local/gradle-{{gradle-version}}-bin.zip"
    curl -L "https://services.gradle.org/distributions/gradle-{{gradle-version}}-bin.zip" -o "$zip"
    unzip -q "$zip" -d .gradle-local
    fi
    printf '%s\n' "$PWD/$gradle_bin"

gradle-bin:
    @just --quiet _gradle-bin

android-toolchain api=android-api:
    just --quiet _android-ndk-home {{api}} >/dev/null
    rustup target add {{android-target}}

android-cli api=android-api: (android-toolchain api)
    #!/usr/bin/env bash
    set -euo pipefail
    ndk="$(just --quiet _android-ndk-home {{api}})"
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/{{android-host}}/bin/aarch64-linux-android{{api}}-clang" \
        cargo build --release -p gbxcam-cli --bin gbxcam --target {{android-target}}
    echo "Built: target/{{android-target}}/release/gbxcam"

android-ffi api=android-api: (android-toolchain api)
    #!/usr/bin/env bash
    set -euo pipefail
    ndk="$(just --quiet _android-ndk-home {{api}})"
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/{{android-host}}/bin/aarch64-linux-android{{api}}-clang" \
        cargo build --release -p gbxcam-ffi --target {{android-target}}
    echo "Built: target/{{android-target}}/release/libgbxcam_ffi.so"

android-all api=android-api: (android-cli api) (android-ffi api)

android-app-libs api=android-api: (android-ffi api)
    mkdir -p apps/android/app/src/main/jniLibs/arm64-v8a
    cp target/{{android-target}}/release/libgbxcam_ffi.so apps/android/app/src/main/jniLibs/arm64-v8a/libgbxcam_ffi.so
    echo "Copied: apps/android/app/src/main/jniLibs/arm64-v8a/libgbxcam_ffi.so"

android-apk api=android-api: (android-app-libs api)
    #!/usr/bin/env bash
    set -euo pipefail
    sdk="$(just --quiet _android-sdk-home)"
    gradle="$(just --quiet _gradle-bin)"
    gradle_home="$PWD/.gradle-home"
    gradle_tmp="$PWD/.gradle-tmp"
    mkdir -p "$gradle_home" "$gradle_tmp"
    cd apps/android
    ANDROID_HOME="$sdk" \
        ANDROID_SDK_ROOT="$sdk" \
        GRADLE_USER_HOME="$gradle_home" \
        JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$gradle_tmp" \
        GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.native=false -Djava.io.tmpdir=$gradle_tmp" \
        "$gradle" --no-daemon :app:assembleDebug
    echo "Built: apps/android/app/build/outputs/apk/debug/app-debug.apk"

android-apk-install api=android-api: (android-apk api)
    adb install --no-streaming -r -t apps/android/app/build/outputs/apk/debug/app-debug.apk

android-app-start:
    adb shell am start -n com.tolik518.gbcam/.MainActivity

android-app-logs:
    adb logcat -c
    adb logcat -s GbcamApp AndroidRuntime

android-pull-dumps output="gbcam-dumps":
    mkdir -p "{{output}}"
    adb pull /sdcard/Android/data/com.tolik518.gbcam/files/dumps "{{output}}"

android-push-save save="dump2/GAMEBOYCAMERA.sav":
    adb shell mkdir -p /sdcard/Android/data/com.tolik518.gbcam/files/dumps
    adb push "{{save}}" /sdcard/Android/data/com.tolik518.gbcam/files/dumps/GAMEBOYCAMERA.sav

android-cli-push api=android-api: (android-cli api)
    #!/usr/bin/env bash
    set -euo pipefail
    command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools."; exit 1; }
    adb get-state >/dev/null
    adb push "target/{{android-target}}/release/gbxcam" "/sdcard/Download/gbxcam"
    echo
    echo "On Termux, run:"
    echo "  cp /sdcard/Download/gbxcam ~/gbxcam"
    echo "  chmod +x ~/gbxcam"

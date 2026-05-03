{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
    platformToolsVersion = "35.0.2";
    ndkVersions = [ "27.1.12297006" ];
    cmakeVersions = [ "3.22.1" ];
    includeNDK = true;
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
  };

  androidSdkRoot = "${androidComposition.androidsdk}/libexec/android-sdk";
  androidNdkRoot = "${androidSdkRoot}/ndk/27.1.12297006";
in
pkgs.mkShell {
  packages = with pkgs; [
    bash
    cacert
    coreutils
    curl
    findutils
    gawk
    git
    gnugrep
    gnumake
    gnused
    gradle
    jdk17
    jq
    nodejs_22
    pnpm
    procps
    sqlite
    unzip
    which
    android-tools
    androidComposition.androidsdk
  ];

  shellHook = ''
    export JAVA_HOME="${pkgs.jdk17.home}"
    export ANDROID_SDK_ROOT="${androidSdkRoot}"
    export ANDROID_HOME="${androidSdkRoot}"
    export ANDROID_NDK_HOME="${androidNdkRoot}"
    export ANDROID_NDK_ROOT="${androidNdkRoot}"
    export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkRoot}/build-tools/35.0.0/aapt2 ''${GRADLE_OPTS:-}"
    export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/35.0.0:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:''${PATH:-}"

    echo ""
    echo "Agor dev shell: adb, aapt, Gradle, Node, pnpm, jq, sqlite available."
    echo "Android smoke: nix run .#agor-android-smoke"
    echo ""
  '';
}

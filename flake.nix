{
  description = "PakEko Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems
        (system:
          let
            pkgs = import nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            };

            androidComposition = pkgs.androidenv.composeAndroidPackages {
              platformVersions = [ "35" "36" ];
              buildToolsVersions = [ "35.0.0" ];
              includeNDK = false;
              includeEmulator = false;
            };

            androidSdk = androidComposition.androidsdk;
            androidHome = "${androidSdk}/libexec/android-sdk";
          in
          {
            default = pkgs.mkShell {
              packages = with pkgs; [
                androidSdk
                git
                gradle
                jdk17
                kotlin
              ];

              ANDROID_HOME = androidHome;
              ANDROID_SDK_ROOT = androidHome;
              JAVA_HOME = pkgs.jdk17.home;

              shellHook = ''
                export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
                echo "Android SDK: $ANDROID_HOME"
                echo "Java: $JAVA_HOME"
              '';
            };
          });
    };
}

{
  description = "foldpen -- an Android phone (Galaxy Z Fold 6) as a stylus tablet for Hyprland";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      # The APK is a build artifact with no runtime closure (two plain files),
      # so it is built from this flake's own nixpkgs, where the Android SDK
      # substitutes from cache.nixos.org, whatever nixpkgs the host uses.
      androidPkgs = import nixpkgs {
        inherit system;
        config = { android_sdk.accept_license = true; allowUnfree = true; };
      };
      pkgs = nixpkgs.legacyPackages.${system};
      apk = androidPkgs.callPackage ./nix/apk.nix { };
    in
    {
      packages.${system} = {
        inherit apk;
        foldpen = pkgs.callPackage ./nix/package.nix { inherit apk; };
        default = self.packages.${system}.foldpen;
      };

      # The daemon is built with the HOST's pkgs (no second python/gtk in the
      # system closure); only the prebuilt APK comes from here.
      overlays.default = final: prev: {
        foldpen = final.callPackage ./nix/package.nix { inherit apk; };
      };

      nixosModules.default = { pkgs, ... }: {
        imports = [ ./nix/module.nix ];
        services.foldpen.package = pkgs.callPackage ./nix/package.nix { inherit apk; };
      };
    };
}

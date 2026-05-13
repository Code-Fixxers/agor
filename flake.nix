{
  description = "Agor development, test, and release workflows";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
        agor = pkgs.lib.evalModules {
          specialArgs = { inherit pkgs system; };
          modules = [ ./nix/agor-flake-module.nix ];
        };
        cfg = agor.config.agor;
      in
      {
        inherit (cfg)
          apps
          checks
          formatter
          packages
          ;

        devShells.default = cfg.devShell;
      }
    );
}

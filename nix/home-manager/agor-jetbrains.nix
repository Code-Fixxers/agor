{ self }:
{ config, lib, pkgs, ... }:

let
  cfg = config.programs.agor-jetbrains;

  inherit (lib)
    literalExpression
    mkEnableOption
    mkIf
    mkOption
    optional
    types;
in
{
  options.programs.agor-jetbrains = {
    enable = mkEnableOption "Agor JetBrains plugin integration";

    jetbrains = {
      pluginPackage = mkOption {
        type = types.nullOr types.package;
        default = self.packages.${pkgs.stdenv.hostPlatform.system}.build-agor-jetbrains-plugin;
        defaultText = literalExpression "inputs.agor.packages.\${pkgs.stdenv.hostPlatform.system}.build-agor-jetbrains-plugin";
        description = "Package providing the Agor JetBrains plugin build helper.";
      };
    };
  };

  config = mkIf cfg.enable {
    home.packages = optional (cfg.jetbrains.pluginPackage != null) cfg.jetbrains.pluginPackage;
  };
}

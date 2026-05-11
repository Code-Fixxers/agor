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
    optionalAttrs
    types;

  nullableStringOrPath = types.nullOr (types.either types.str types.path);
  tokenSourceSet = value: value != null && toString value != "";

  hermesProxyCommand = lib.getExe cfg.hermes.proxyPackage;

  acpConfig = {
    agents = {
      Hermes = {
        command = hermesProxyCommand;
        env =
          {
            AGOR_URL = cfg.agor.url;
            HERMES_URL = cfg.hermes.url;
            HERMES_MODEL = cfg.hermes.model;
          }
          // optionalAttrs (tokenSourceSet cfg.agor.tokenCommand) {
            AGOR_TOKEN_COMMAND = cfg.agor.tokenCommand;
          }
          // optionalAttrs (tokenSourceSet cfg.agor.tokenFile) {
            AGOR_TOKEN_FILE = toString cfg.agor.tokenFile;
          }
          // optionalAttrs (tokenSourceSet cfg.hermes.tokenCommand) {
            HERMES_TOKEN_COMMAND = cfg.hermes.tokenCommand;
          }
          // optionalAttrs (tokenSourceSet cfg.hermes.tokenFile) {
            HERMES_TOKEN_FILE = toString cfg.hermes.tokenFile;
          };
      };
    };
  };
in
{
  options.programs.agor-jetbrains = {
    enable = mkEnableOption "Agor JetBrains ACP integration";

    agor = {
      url = mkOption {
        type = types.str;
        default = "http://localhost:3030";
        description = "Agor daemon URL exposed to the JetBrains ACP agent environment.";
      };

      tokenCommand = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "pass show agor/token";
        description = "Command that prints an Agor bearer token. Mutually exclusive with agor.tokenFile.";
      };

      tokenFile = mkOption {
        type = nullableStringOrPath;
        default = null;
        example = literalExpression ''"''${config.home.homeDirectory}/.config/agor/token"'';
        description = "File containing an Agor bearer token. Mutually exclusive with agor.tokenCommand.";
      };
    };

    hermes = {
      url = mkOption {
        type = types.str;
        default = "http://localhost:8642";
        description = "Hermes OpenAI-compatible API URL used by the ACP proxy.";
      };

      tokenCommand = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "pass show hermes/token";
        description = "Command that prints a Hermes bearer token. Mutually exclusive with hermes.tokenFile.";
      };

      tokenFile = mkOption {
        type = nullableStringOrPath;
        default = null;
        example = literalExpression ''"''${config.home.homeDirectory}/.config/hermes/token"'';
        description = "File containing a Hermes bearer token. Mutually exclusive with hermes.tokenCommand.";
      };

      model = mkOption {
        type = types.str;
        default = "hermes";
        description = "Hermes model name forwarded to the ACP proxy.";
      };

      proxyPackage = mkOption {
        type = types.package;
        default = self.packages.${pkgs.stdenv.hostPlatform.system}.hermes-acp-proxy;
        defaultText = literalExpression "inputs.agor.packages.\${pkgs.stdenv.hostPlatform.system}.hermes-acp-proxy";
        description = "Package providing the hermes-acp-proxy executable.";
      };
    };

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
    assertions = [
      {
        assertion = !(tokenSourceSet cfg.agor.tokenCommand && tokenSourceSet cfg.agor.tokenFile);
        message = "programs.agor-jetbrains.agor.tokenCommand and agor.tokenFile are mutually exclusive.";
      }
      {
        assertion = tokenSourceSet cfg.hermes.tokenCommand || tokenSourceSet cfg.hermes.tokenFile;
        message = "programs.agor-jetbrains requires either hermes.tokenCommand or hermes.tokenFile.";
      }
      {
        assertion = !(tokenSourceSet cfg.hermes.tokenCommand && tokenSourceSet cfg.hermes.tokenFile);
        message = "programs.agor-jetbrains.hermes.tokenCommand and hermes.tokenFile are mutually exclusive.";
      }
    ];

    home.packages =
      [ cfg.hermes.proxyPackage ]
      ++ optional (cfg.jetbrains.pluginPackage != null) cfg.jetbrains.pluginPackage;

    home.file.".jetbrains/acp.json".text = builtins.toJSON acpConfig + "\n";
  };
}

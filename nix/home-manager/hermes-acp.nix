{ config, lib, ... }:

let
  cfg = config.programs.hermes-acp;

  inherit (lib)
    mkEnableOption
    mkIf
    mkOption
    optional
    optionalString
    types;

  agentConfig =
    if cfg.mode == "local" then {
      command = cfg.local.command;
      args = cfg.local.args;
    } else if cfg.mode == "docker" then {
      command = cfg.docker.executable;
      args = [ "exec" "-i" cfg.docker.container cfg.docker.command ] ++ cfg.docker.args;
    } else if cfg.mode == "compose" then {
      command = cfg.compose.executable;
      args =
        [ "compose" ]
        ++ optional (cfg.compose.projectDirectory != null) "--project-directory"
        ++ optional (cfg.compose.projectDirectory != null) (toString cfg.compose.projectDirectory)
        ++ [ "exec" "-T" cfg.compose.service cfg.compose.command ]
        ++ cfg.compose.args;
    } else {
      command = cfg.ssh.executable;
      args =
        cfg.ssh.options
        ++ [ cfg.ssh.host cfg.ssh.dockerExecutable "exec" "-i" cfg.ssh.container cfg.ssh.command ]
        ++ cfg.ssh.args;
    };

  acpConfig = {
    default_mcp_settings = cfg.defaultMcpSettings;
    agent_servers = {
      ${cfg.agentName} = agentConfig // {
        env = cfg.extraEnv;
      };
    };
  };

  nonEmpty = value: value != null && toString value != "";
in
{
  options.programs.hermes-acp = {
    enable = mkEnableOption "Hermes as a JetBrains ACP agent";

    agentName = mkOption {
      type = types.str;
      default = "Hermes";
      description = "Name shown for the Hermes ACP agent in JetBrains AI Chat.";
    };

    mode = mkOption {
      type = types.enum [ "local" "docker" "compose" "ssh-docker" ];
      default = "local";
      description = "How JetBrains should launch native Hermes ACP stdio.";
    };

    acpConfigPath = mkOption {
      type = types.str;
      default = ".jetbrains/acp.json";
      description = "Home-relative JetBrains ACP configuration path.";
    };

    extraEnv = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = {
        HERMES_CONFIG = "/home/me/.config/hermes/config.toml";
      };
      description = "Extra environment variables for the Hermes ACP subprocess.";
    };

    defaultMcpSettings = mkOption {
      type = types.attrsOf types.bool;
      default = {
        use_custom_mcp = true;
        use_idea_mcp = true;
      };
      description = "Default MCP settings written to JetBrains acp.json for local ACP agents.";
    };

    local = {
      command = mkOption {
        type = types.str;
        default = "hermes";
        description = "Local Hermes executable.";
      };

      args = mkOption {
        type = types.listOf types.str;
        default = [ "acp" ];
        description = "Arguments for launching local native Hermes ACP.";
      };
    };

    docker = {
      executable = mkOption {
        type = types.str;
        default = "docker";
        description = "Docker executable used for containerized Hermes ACP.";
      };

      container = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "hermes";
        description = "Container name or ID for docker exec mode.";
      };

      command = mkOption {
        type = types.str;
        default = "hermes";
        description = "Hermes executable inside the container.";
      };

      args = mkOption {
        type = types.listOf types.str;
        default = [ "acp" ];
        description = "Arguments for native Hermes ACP inside the container.";
      };
    };

    compose = {
      executable = mkOption {
        type = types.str;
        default = "docker";
        description = "Docker executable used for Docker Compose Hermes ACP.";
      };

      projectDirectory = mkOption {
        type = types.nullOr (types.either types.str types.path);
        default = null;
        description = "Optional Docker Compose project directory.";
      };

      service = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "hermes";
        description = "Docker Compose service that runs Hermes.";
      };

      command = mkOption {
        type = types.str;
        default = "hermes";
        description = "Hermes executable inside the Compose service.";
      };

      args = mkOption {
        type = types.listOf types.str;
        default = [ "acp" ];
        description = "Arguments for native Hermes ACP inside the Compose service.";
      };
    };

    ssh = {
      executable = mkOption {
        type = types.str;
        default = "ssh";
        description = "SSH executable for remote containerized Hermes ACP.";
      };

      options = mkOption {
        type = types.listOf types.str;
        default = [ "-T" "-o" "BatchMode=yes" "-o" "ServerAliveInterval=30" ];
        description = "SSH options. Keep tty allocation disabled so stdout remains ACP JSON-RPC only.";
      };

      host = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "hermes-host";
        description = "SSH host for remote Docker mode.";
      };

      dockerExecutable = mkOption {
        type = types.str;
        default = "docker";
        description = "Docker executable on the remote host.";
      };

      container = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "hermes";
        description = "Remote container name or ID for docker exec mode.";
      };

      command = mkOption {
        type = types.str;
        default = "hermes";
        description = "Hermes executable inside the remote container.";
      };

      args = mkOption {
        type = types.listOf types.str;
        default = [ "acp" ];
        description = "Arguments for native Hermes ACP inside the remote container.";
      };
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.mode != "docker" || nonEmpty cfg.docker.container;
        message = "programs.hermes-acp.docker.container is required when mode = \"docker\".";
      }
      {
        assertion = cfg.mode != "compose" || nonEmpty cfg.compose.service;
        message = "programs.hermes-acp.compose.service is required when mode = \"compose\".";
      }
      {
        assertion = cfg.mode != "ssh-docker" || (nonEmpty cfg.ssh.host && nonEmpty cfg.ssh.container);
        message = "programs.hermes-acp.ssh.host and ssh.container are required when mode = \"ssh-docker\".";
      }
    ];

    home.file.${cfg.acpConfigPath}.text = builtins.toJSON acpConfig + "\n";

    warnings = optional (cfg.mode == "ssh-docker") ''
      programs.hermes-acp uses SSH stdio transport. Ensure the remote login emits no banners, MOTD, prompts, or stdout logs before Hermes ACP starts${optionalString (cfg.ssh.options != []) "."}
    '';
  };
}

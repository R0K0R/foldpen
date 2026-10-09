{ config, lib, ... }:

let
  cfg = config.services.foldpen;
in
{
  options.services.foldpen = {
    enable = lib.mkEnableOption "foldpen, an Android phone as a stylus tablet for Hyprland";
    package = lib.mkOption { type = lib.types.package; description = "The foldpen package."; };
    users = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = "Accounts allowed to create the virtual pen/touch devices (added to `uinput`).";
    };
    port = lib.mkOption { type = lib.types.port; default = 7392; };
    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = ''
        Open the TCP port so the phone can connect over Wi-Fi. Not needed over
        USB (`foldpen usb` tunnels the port with adb reverse). The phone must
        present the paired token either way.
      '';
    };
    extraArgs = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [ "--max-interval" "2" "--frame-px" "960" ];
      description = "Extra arguments for `foldpen daemon`.";
    };
  };

  config = lib.mkIf cfg.enable {
    hardware.uinput.enable = true;
    users.users = lib.genAttrs cfg.users (_: { extraGroups = [ "uinput" ]; });
    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
    environment.systemPackages = [ cfg.package ];

    systemd.user.services.foldpen = {
      description = "foldpen phone stylus daemon";
      partOf = [ "graphical-session.target" ];
      after = [ "graphical-session.target" ];
      wantedBy = [ "graphical-session.target" ];
      # hyprctl needs the session's HYPRLAND_INSTANCE_SIGNATURE, which uwsm
      # exports into the user manager before graphical-session.target.
      serviceConfig = {
        ExecStart = lib.escapeShellArgs ([ (lib.getExe cfg.package) "daemon" "--port" (toString cfg.port) ] ++ cfg.extraArgs);
        Restart = "on-failure";
        RestartSec = 2;
      };
    };
  };
}

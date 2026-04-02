{ pkgs, ... }:

{
  packages = [
    pkgs.gradle_9
  ];

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk25;
    gradle.enable = false;
    maven.enable = false;
  };
}

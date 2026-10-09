{ lib, stdenvNoCC, makeWrapper, python3, gobject-introspection, gtk3, gtk-layer-shell
, pango, gdk-pixbuf, atk, harfbuzz
, grim, hyprland, iproute2, android-tools, jdk17_headless, libnotify, apk }:

let
  python = python3.withPackages (ps: [ ps.evdev ps.pygobject3 ps.pycairo ]);
in
stdenvNoCC.mkDerivation {
  pname = "foldpen";
  version = "1";
  src = ../daemon;
  nativeBuildInputs = [ makeWrapper ];
  installPhase = ''
    mkdir -p $out/share/foldpen $out/bin
    cp foldpen.py overlay.py $out/share/foldpen/
    makeWrapper ${python}/bin/python3 $out/bin/foldpen \
      --add-flags $out/share/foldpen/foldpen.py \
      --set FOLDPEN_APK ${apk} \
      --prefix GI_TYPELIB_PATH : ${lib.makeSearchPath "lib/girepository-1.0" (map (p: p.out) [ gtk3 gtk-layer-shell gobject-introspection pango gdk-pixbuf atk harfbuzz ])} \
      --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath [ gtk-layer-shell ]} \
      --suffix PATH : ${lib.makeBinPath [ grim hyprland iproute2 android-tools jdk17_headless libnotify ]}
  '';
  passthru = { inherit apk; };
  meta.mainProgram = "foldpen";
}

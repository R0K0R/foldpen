{ lib, stdenvNoCC, runCommand, makeWrapper, python3, gobject-introspection, gtk3, gtk-layer-shell
, pango, gdk-pixbuf, atk, harfbuzz
, grim, hyprland, iproute2, android-tools, jdk17_headless, libnotify, apk }:

let
  python = python3.withPackages (ps: [ ps.evdev ps.pygobject3 ps.pycairo ]);

  # gobject-introspection's cairo-1.0.typelib names its libcairo-gobject by
  # store path, and in a tuned or cross package set that is the BUILD
  # platform's cairo, not the one GTK links. pygobject then loads that second
  # copy (on the first cairo.Region, the overlay's input shape), both copies
  # register CairoRegion, GDK's registration returns 0, and the overlay
  # segfaults on its first paint (g_boxed_type_register_static assertion).
  # This copy of the typelib, first on the path, names the bare soname
  # instead, which resolves to the libcairo-gobject GTK already loaded. The
  # string is patched in place (NUL-padded, so no offset moves): a
  # g-ir-generate / g-ir-compiler round trip segfaults on this typelib.
  cairoTypelib = runCommand "foldpen-cairo-typelib" { nativeBuildInputs = [ python3 ]; } ''
    mkdir -p $out/lib/girepository-1.0
    python3 - ${gobject-introspection}/lib/girepository-1.0/cairo-1.0.typelib \
      $out/lib/girepository-1.0/cairo-1.0.typelib <<'PY'
    import re, sys
    d = open(sys.argv[1], "rb").read()
    m = re.search(rb"/[^\0]*/libcairo-gobject\.so\.2(?=\0)", d)
    if m:
        new = b"libcairo-gobject.so.2"
        d = d[:m.start()] + new + b"\0" * (m.end() - m.start() - len(new)) + d[m.end():]
    open(sys.argv[2], "wb").write(d)
    PY
  '';
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
      --prefix GI_TYPELIB_PATH : ${lib.makeSearchPath "lib/girepository-1.0" (map (p: p.out) [ cairoTypelib gtk3 gtk-layer-shell gobject-introspection pango gdk-pixbuf atk harfbuzz ])} \
      --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath [ gtk-layer-shell ]} \
      --suffix PATH : ${lib.makeBinPath [ grim hyprland iproute2 android-tools jdk17_headless libnotify ]}
  '';
  passthru = { inherit apk; };
  meta.mainProgram = "foldpen";
}

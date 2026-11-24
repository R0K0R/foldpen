{ lib, stdenvNoCC, androidenv, jdk17_headless, zip
  # false: a finger never becomes the pen (fingers are always touch; only the
  # S Pen draws). Written into android/src/dev/foldpen/Flags.java.
, fingerPen ? true
}:

# Built without Gradle: the app uses framework APIs only (no AndroidX, no
# resources besides the manifest), so aapt2 + javac + d8 + zipalign is all of
# it. Left UNSIGNED on purpose -- `foldpen install` signs it with a key kept in
# ~/.local/state/foldpen, so no private key lives in a repo or the store, and
# every rebuild still installs as an update of the same app.
let
  buildTools = "35.0.0";
  platform = "35";
  sdk = (androidenv.composeAndroidPackages {
    platformVersions = [ platform ];
    buildToolsVersions = [ buildTools ];
    includeEmulator = false;
    includeSystemImages = false;
    includeNDK = false;
  }).androidsdk;
  root = "${sdk}/libexec/android-sdk";
  bt = "${root}/build-tools/${buildTools}";
  jar = "${root}/platforms/android-${platform}/android.jar";
in
stdenvNoCC.mkDerivation {
  pname = "foldpen-apk" + lib.optionalString (!fingerPen) "-no-finger-pen";
  version = "1";
  src = ../android;
  nativeBuildInputs = [ jdk17_headless zip ];
  buildPhase = ''
    runHook preBuild
    substituteInPlace src/dev/foldpen/Flags.java \
      --replace-fail "FINGER_PEN = true;" "FINGER_PEN = ${lib.boolToString fingerPen};"
    ${bt}/aapt2 link -o base.apk -I ${jar} --manifest AndroidManifest.xml \
      --min-sdk-version 30 --target-sdk-version ${platform} \
      --version-code 1 --version-name 1
    mkdir classes dex
    javac -encoding UTF-8 --release 11 -Xlint:-options -classpath ${jar} -d classes $(find src -name '*.java')
    ${bt}/d8 --release --min-api 30 --lib ${jar} --output dex $(find classes -name '*.class')
    (cd dex && zip -q ../base.apk classes.dex)
    ${bt}/zipalign -f -p 4 base.apk aligned.apk
    runHook postBuild
  '';
  installPhase = ''
    mkdir -p $out
    cp aligned.apk $out/foldpen-unsigned.apk
    cp ${bt}/lib/apksigner.jar $out/
  '';
}

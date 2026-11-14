// Run after `npx cap add android`. Idempotent.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const appId = require(path.join(root, 'capacitor.config.json')).appId;
const android = path.join(root, 'android');
if (!fs.existsSync(android)) throw new Error('android/ missing - run `npx cap add android` first');

// 1. Java sources (package line is rewritten to the appId)
const srcDir = path.join(root, 'native', 'java');
const dstDir = path.join(android, 'app/src/main/java', ...appId.split('.'));
fs.mkdirSync(dstDir, { recursive: true });
for (const f of fs.readdirSync(srcDir)) {
  const code = fs.readFileSync(path.join(srcDir, f), 'utf8').replace(/^package .*;/m, `package ${appId};`);
  fs.writeFileSync(path.join(dstDir, f), code);
}

// 2. Manifest
const mPath = path.join(android, 'app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(mPath, 'utf8');
if (!m.includes('.PopupActivity')) {
  const perms = fs.readFileSync(path.join(root, 'native/manifest-permissions.xml'), 'utf8');
  const comps = fs.readFileSync(path.join(root, 'native/manifest-components.xml'), 'utf8');
  m = m.replace('<application', () => perms + '\n    <application android:extractNativeLibs="true"');
  m = m.replace('</application>', () => comps + '\n    </application>');
  fs.writeFileSync(mPath, m);
}

// 3. Shizuku + yt-dlp engine dependencies, native-lib packaging, minSdk
const gPath = path.join(android, 'app/build.gradle');
let g = fs.readFileSync(gPath, 'utf8');
if (!g.includes('dev.rikka.shizuku')) {
  g = g.replace(/dependencies\s*\{/, (x) =>
    x + '\n    implementation "dev.rikka.shizuku:api:13.1.5"\n    implementation "dev.rikka.shizuku:provider:13.1.5"'
      + '\n    implementation "io.github.junkfood02.youtubedl-android:library:0.18.1"'
      + '\n    implementation "io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1"'
      + '\n    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.9.24"');
  // yt-dlp's python/ffmpeg ship as native libs that must be extracted to disk; arm64 only (modern phones) keeps the APK smaller.
  g = g.replace(/defaultConfig\s*\{/, (x) => x + "\n        ndk { abiFilters 'arm64-v8a' }");
  g = g.replace(/android\s*\{/, (x) => x + '\n    packaging { jniLibs { useLegacyPackaging = true } }');
  fs.writeFileSync(gPath, g);
}
const vPath = path.join(android, 'variables.gradle');
if (fs.existsSync(vPath)) {
  let v = fs.readFileSync(vPath, 'utf8');
  v = v.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 30');
  fs.writeFileSync(vPath, v);
}
console.log('Android project patched for', appId);

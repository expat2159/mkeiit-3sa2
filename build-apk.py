#!/usr/bin/env python3
"""Build a test APK with Android SDK + ECJ, without Gradle or Android Studio."""
import argparse
from pathlib import Path
import subprocess
import zipfile
import shutil

p=argparse.ArgumentParser()
p.add_argument('--sdk', required=True)
p.add_argument('--ecj', required=True, help='Path to ecj-3.38.0.jar')
args=p.parse_args()
root=Path(__file__).resolve().parent
sdk=Path(args.sdk).resolve()
ecj=Path(args.ecj).resolve()
tools=sdk/'build-tools/35.0.0'
android=sdk/'platforms/android-35/android.jar'
build=root/'build'
dist=root/'dist'
build.mkdir(exist_ok=True)
dist.mkdir(exist_ok=True)
classes=build/'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(exist_ok=True)
def run(*cmd):
    subprocess.run([str(c) for c in cmd],cwd=root,check=True)
run('java','-jar',ecj,'-1.8','-encoding','UTF-8','-bootclasspath',str(android)+':'+str(tools/'core-lambda-stubs.jar'),
    '-cp',root/'app/libs/jsoup-1.18.3.jar','-d',classes,*sorted((root/'app/src/main/java').rglob('*.java')))
run(tools/'aapt2','compile','--dir',root/'app/src/main/res','-o',build/'resources.zip')
manifest=build/'AndroidManifest.xml'
manifest.write_text((root/'app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="ru.mkeiit.schedule" ',1))
run(tools/'aapt2','link','-o',build/'base.apk','-I',android,'--manifest',manifest,
    '-A',root/'app/src/main/assets','--version-code','14','--version-name','1.13','--min-sdk-version','26','--target-sdk-version','35',build/'resources.zip')
with zipfile.ZipFile(build/'classes.jar','w') as jar:
    for f in sorted(classes.rglob('*.class')): jar.write(f,f.relative_to(classes))
run(tools/'d8','--lib',android,'--min-api','26','--output',build,build/'classes.jar',root/'app/libs/jsoup-1.18.3.jar')
with zipfile.ZipFile(build/'base.apk','a',zipfile.ZIP_DEFLATED) as apk:
    for f in build.glob('classes*.dex'): apk.write(f,f.name)
run(tools/'zipalign','-f','4',build/'base.apk',build/'aligned.apk')
key=build/'test-signing.keystore'
if not key.exists():
    run('keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey',
        '-dname','CN=3SA2 Test Build','-keyalg','RSA','-keysize','2048','-validity','10000')
output=dist/'3SA2-raspisanie-1.13.apk'
run(tools/'apksigner','sign','--ks',key,'--ks-pass','pass:android','--key-pass','pass:android','--out',output,build/'aligned.apk')
run(tools/'apksigner','verify','--verbose',output)
print(output)

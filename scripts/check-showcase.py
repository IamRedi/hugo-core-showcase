"""Verify this fresh-history evaluation snapshot; never access a real installation."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--built',action='store_true')
parser.add_argument('--runtime-only',action='store_true')
args=parser.parse_args()
issues=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
files=[]
if not args.runtime_only:
 files=[n for n in subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0') if n]
 if not files:issues.append('No tracked publication files')
 manifest=json.loads((root/'docs/assets/media-manifest.json').read_text(encoding='utf-8'))
 media={x['path']:x['sha256'] for x in manifest['media']}
 product=json.loads((root/'docs/product-assets.json').read_text(encoding='utf-8'))
 assets={x['path']:x['sha256'] for x in product['assets']}
 for name in files:
  p=root/name
  if re.search(r'(^|/)(?:AGENTS|CLAUDE)\.md$|(^|/)(?:local|secrets)\.properties$|\.(?:apk|apks|jks|keystore|sqlite|db|pem|key|pfx|p12|zip)$',name,re.I):issues.append(name+': private/runtime artifact')
  if p.name.startswith('.env') and p.name!='.env.example':issues.append(name+': environment file')
  raw=p.read_bytes()
  if p.suffix.lower() in ('.png','.jpg','.jpeg','.webp','.mp4','.srt','.jar','.svg'):
   if sha(p)!=(media.get(name) or assets.get(name)):issues.append(name+': unreviewed media/binary')
  if b'\0' not in raw and name!='scripts/check-showcase.py':
   text=raw.decode('utf-8',errors='replace')
   if re.search(r'[A-Za-z]:[/\\](?:Users|All my app)[/\\]|/home/[A-Za-z0-9_.-]+/',text):issues.append(name+': private machine path')
   for m in re.finditer(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}',text):
    if not(name.startswith('app/src/test/') and m.group()=='AA:BB:CC:DD:EE:FF'):issues.append(name+': device address')
   if re.search(r'(?<![\w-])(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})',text):issues.append(name+': credential prefix')
  if p.suffix=='.md':
   text=raw.decode('utf-8',errors='replace')
   for m in re.finditer(r'\]\(([^)]+)\)',text):
    target=m.group(1).split('#')[0]
    if not target or re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',target):continue
    if not (p.parent/target).exists():issues.append(name+': broken relative link '+target)
 source=json.loads((root/'docs/source-snapshot.json').read_text(encoding='utf-8'))
 for item in source['original_production_kotlin_unchanged']:
  if sha(root/item['path'])!=item['sha256_lf']:issues.append(item['path']+': original Kotlin drift')

if args.built or args.runtime_only:
 ns='{http://schemas.android.com/apk/res/android}'
 candidates=list((root/'showcase/build/intermediates/merged_manifests/debug').rglob('AndroidManifest.xml'))
 if len(candidates)!=1:issues.append('Expected exactly one merged showcase manifest')
 else:
  tree=ET.parse(candidates[0]).getroot()
  if tree.get('package')!='io.redi.hugo.showcase':issues.append('Wrong evaluation application ID')
  for permission in tree.findall('uses-permission'):
   if permission.get(ns+'name','').startswith('android.permission.'):issues.append('Forbidden platform permission in evaluation APK')
  names=[n.get(ns+'name','') for n in tree.iter()]
  if 'io.redi.robot.MainActivity' in names or any('ConnectedDeviceService' in n for n in names):issues.append('Live component registered')
  classes=root/'showcase/build/tmp/kotlin-classes/debug/io/redi/robot'
  for forbidden in ('body/looi','platform/voice','platform/vision','platform/service','platform/transport','platform/storage/LooiProviderIdentityRepository.class','MainActivity.class'):
   if (classes/forbidden).exists():issues.append('Live source entered evaluation classes: '+forbidden)
  for required in ('showcase/HugoShowcaseActivity.class','safety/DefaultSafetyController.class','memory/semantic/SemanticFactFactory.class'):
   if not (classes/required).exists():issues.append('Missing actual component: '+required)

print(json.dumps({'tracked_files':len(files),'built_checked':bool(args.built or args.runtime_only),'issues':issues},indent=2))
raise SystemExit(bool(issues))

#!/usr/bin/env python3
"""Validate/apply the consolidated WLAN source patch on its pinned clean base.

Does not build, install or touch a device. Use --verify for a patched tree.
Unrelated files are left alone; every touched file's exact hash is checked.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('tree',type=Path)
    mode=p.add_mutually_exclusive_group()
    mode.add_argument('--apply',action='store_true')
    mode.add_argument('--verify',action='store_true')
    args=p.parse_args()
    bundle=Path(__file__).resolve().parent
    manifest=json.loads((bundle/'source-manifest.json').read_text())
    patch=bundle/'wlan-consolidated.patch'
    if digest(patch)!=manifest['patch_sha256']:raise SystemExit('Patch checksum mismatch')
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=args.tree,text=True).strip()
    if head!=manifest['base_commit']:raise SystemExit('Wrong WLAN baseline: '+head)
    expected='after_sha256' if args.verify else 'before_sha256'
    for entry in manifest['files']:
        rel=Path(entry['path'])
        if rel.is_absolute() or '..' in rel.parts:raise SystemExit('Unsafe manifest path')
        target=args.tree/rel
        if target.is_symlink() or digest(target)!=entry[expected]:
            raise SystemExit('Source hash mismatch: '+str(rel))
    if args.verify:
        print('All 44 candidate source/header hashes verified.')
        return
    subprocess.run(['git','apply','--check',str(patch)],cwd=args.tree,check=True)
    if args.apply:
        subprocess.run(['git','apply',str(patch)],cwd=args.tree,check=True)
        for entry in manifest['files']:
            if digest(args.tree/entry['path'])!=entry['after_sha256']:
                raise SystemExit('Post-apply mismatch: '+entry['path'])
        print('Applied and verified consolidated WLAN stack. Not built or deployed.')
    else:print('Baseline and patch check passed. Use --apply to apply locally.')

if __name__=='__main__':main()

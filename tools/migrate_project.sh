#!/usr/bin/env bash
# Migrate one project to the project-root layout. Requires Python 3.8+ (stdlib only).
# Preview: ./tools/migrate_project.sh '/path/My Project/Old Name.smedit'
# Apply:   ./tools/migrate_project.sh --apply '/path/My Project/Old Name.smedit'
set -euo pipefail
if ! command -v python3 >/dev/null 2>&1; then
    echo 'Python 3.8+ is required for safe JSON handling and backup verification.' >&2
    exit 1
fi
exec python3 - "$@" <<'PY'
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import uuid


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def inventory(root):
    """Include empty directories; never follow links or read devices/FIFOs."""
    result = {}
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            path = Path(directory) / name
            mode = path.lstat().st_mode
            require(stat.S_ISREG(mode) or stat.S_ISDIR(mode),
                    'Symlink/special file requires manual review: ' + str(path))
            result[path.relative_to(root).as_posix()] = (
                None if stat.S_ISDIR(mode) else digest(path))
    return result


def read_json(text):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=unique_keys)


def migrate(args):
    supplied = Path(args.project).expanduser().absolute()
    require(not supplied.is_symlink(), 'Select the real project file, not a symlink.')
    require(supplied.is_file() and supplied.suffix == '.smedit', 'Select an existing .smedit file.')
    source = supplied.resolve()
    root = source.parent
    require(root != Path(root.anchor) and root != Path.home().resolve(),
            'The project must have its own directory, not a filesystem root or home directory.')
    require(root.name == root.name.strip() and not root.name.endswith('.') and
            not re.search(r'[\x00-\x1f<>:"/\\|?*]', root.name) and
            not re.fullmatch(r'(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', root.name),
            'The project folder name is not a portable filename; rename it first.')
    active = [p for p in root.iterdir() if p.is_file() and p.suffix.lower() == '.smedit']
    require(active == [source], 'Multiple root .smedit files: separate the projects before migrating.')
    require(not root.name.endswith(('_smedit', '_states')) and root.name not in
            ('smedit', '.smedit', 'states', 'emu_states', 'legacy-project'),
            'Select the authoritative root project, not a nested historical/support copy.')
    before = inventory(root)
    text = source.read_text(encoding='utf-8')
    project = read_json(text)
    require(isinstance(project, dict), 'The project must be a JSON object.')
    recorded = project.get('romPath')
    require(isinstance(recorded, str) and recorded.strip(), 'Missing romPath in the project.')
    # Recognize legacy absolute Windows paths even when running on macOS/Linux.
    rom_name = recorded.replace('\\', '/').rsplit('/', 1)[-1]
    require(rom_name not in ('', '.', '..') and Path(rom_name).suffix.lower() in ('.smc', '.sfc'),
            'romPath must identify an .smc or .sfc base ROM.')
    updated = copy.deepcopy(project)
    updated['romPath'] = rom_name
    if not updated.get('projectId'):
        # Match Java UUID.nameUUIDFromBytes for the OLD path, retaining last-room identity.
        updated['projectId'] = str(uuid.UUID(bytes=hashlib.md5(str(source).encode('utf-8')).digest(), version=3))
    rom = root / rom_name
    notices = []
    if rom.is_file():
        contents = rom.read_bytes()
        if len(contents) % 0x8000 == 512:
            contents = contents[512:]
        require(bool(contents), 'The base ROM is empty.')
        identity = {'originalFileName': rom_name, 'sha256': hashlib.sha256(contents).hexdigest(),
                    'size': len(contents)}
        known = updated.get('baseRom')
        if known is not None:
            require(isinstance(known, dict) and known.get('sha256') == identity['sha256'] and
                    known.get('size') == identity['size'], 'Base ROM fingerprint mismatch; no files changed.')
        else:
            # Without an existing fingerprint, do not silently substitute a different local ROM.
            recorded_file = Path(recorded).expanduser()
            if not recorded_file.is_absolute():
                recorded_file = root / recorded_file
            if recorded_file.is_file() and recorded_file.resolve() != rom:
                require(digest(recorded_file) == digest(rom),
                        'Recorded ROM differs from the root copy. Resolve the base ROM before migrating.')
            updated['baseRom'] = identity
            notices.append('Recording the identity of the existing root base ROM: ' + rom_name)
    else:
        notices.append('Base ROM is not in the project root. OPEN will ask you to locate/import ' + rom_name)

    # Plan against a virtual tree so every destination is checked BEFORE any writes.
    tree = dict(before)
    moves = []

    def move(old, new):
        require(old in tree, 'Missing migration source: ' + old)
        require(new not in tree, 'Destination already exists (no automatic merging): ' + new)
        for parent in Path(new).parents:
            name = parent.as_posix()
            require(name not in tree or tree[name] is None, 'Destination parent is a file: ' + name)
        entries = [p for p in tree if p == old or p.startswith(old + '/')]
        for entry in entries:
            tree[new + entry[len(old):]] = tree.pop(entry)
        for parent in Path(new).parents:
            if parent.as_posix() != '.':
                tree.setdefault(parent.as_posix(), None)
        moves.append((old, new))

    bases = {source.stem, root.name, Path(rom_name).stem}
    for old in sorted({name + '_smedit' for name in bases} | {'.smedit'}):
        if old in tree:
            require(tree[old] is None, 'Expected a directory: ' + old)
            move(old, 'smedit')
    for old in sorted({name + '_states' for name in bases} | {'emu_states'}):
        if old in tree:
            require(tree[old] is None, 'Expected a directory: ' + old)
            move(old, 'states')
    for name in ('modules', 'overrides'):
        old = 'smedit/asm/' + name
        if old in tree:
            require(tree[old] is None, 'Expected an authored ASM directory: ' + old)
            move(old, 'asm/' + name)
    if 'smedit/exports' in tree:
        move('smedit/exports', 'exports')
    for old in sorted(list(tree)):
        if not old.startswith('smedit/') or old.count('/') != 1 or tree[old] is None:
            continue
        name = Path(old).name
        ext = Path(name).suffix.lower()
        destination = None
        if ext in ('.smedit', '.smc', '.sfc', '.ips'):
            destination = 'archive/legacy-project/'
        elif ext in ('.mid', '.spc'):
            destination = 'exports/audio/'
        elif ext == '.png':
            if name.startswith('tileset_'):
                destination = 'data/legacy-assets/'
            elif re.fullmatch(r'(cre|ure_\d+)\.png', name):
                destination = 'exports/graphics/'
            else:
                destination = 'exports/mapshots/'
        if destination:
            move(old, destination + name)
    target_name = root.name + '.smedit'
    if source.name != target_name:
        move(source.name, target_name)

    changes = {}
    if updated != project:
        # Preserve all unknown fields and schema markers. Never upgrade gameplay data here.
        changes[target_name] = (json.dumps(updated, ensure_ascii=False, separators=(',', ':'),
                                           allow_nan=False) + '\n').encode('utf-8')
    ignore = root / '.gitignore'
    require(not ignore.exists() or ignore.is_file(), '.gitignore must be a regular file.')
    ignore_text = ignore.read_text(encoding='utf-8') if ignore.exists() else ''
    rules = ('*.[sS][mM][cC]', '*.[sS][fF][cC]', '/smedit/', '/states/', '/exports/')
    # Append after negation rules so private outputs are not accidentally re-included.
    block = '# SMEDIT local ROMs and generated files (authored asm/ and data/ stay shareable).\n' + '\n'.join(rules) + '\n'
    if not ignore_text.endswith(block):
        changes['.gitignore'] = (ignore_text + ('\n' if ignore_text and not ignore_text.endswith('\n') else '') + block).encode('utf-8')

    print('Project: ' + str(source))
    for notice in notices:
        print('Note: ' + notice)
    for old, new in moves:
        print('MOVE ' + old + ' -> ' + new)
    for name in changes:
        print('UPDATE ' + name)
    if not moves and not changes:
        print('Already migrated; no changes or backup needed.')
        return
    if not args.apply:
        print('Preview only. Close SMEDIT, then rerun with --apply. No files changed.')
        return

    backup_parent = Path(args.backup_dir).expanduser().resolve()
    require(backup_parent != root and root not in backup_parent.parents,
            'Backups must be outside the project directory.')
    backup_parent.mkdir(parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix=root.name + '-migration-', dir=str(backup_parent)))
    print('Backup: ' + str(backup), flush=True)
    shutil.copytree(root, backup / root.name, symlinks=True)
    require(inventory(backup / root.name) == before and inventory(root) == before,
            'Files changed while backing up; migration stopped before any project writes.')
    journal = {'project': str(source), 'moves': moves, 'updatedFiles': list(changes)}
    (backup / 'migration.json').write_text(json.dumps(journal, indent=2) + '\n', encoding='utf-8')
    try:
        for old, new in moves:
            destination = root / new
            require(not destination.exists(), 'Destination appeared during migration: ' + new)
            destination.parent.mkdir(parents=True, exist_ok=True)
            (root / old).rename(destination)
        for name, content in changes.items():
            destination = root / name
            fd, temporary = tempfile.mkstemp(prefix='.migration-', dir=str(root))
            with os.fdopen(fd, 'wb') as stream:
                stream.write(content)
            if destination.exists():
                shutil.copymode(destination, temporary)
            os.replace(temporary, destination)
            tree[name] = hashlib.sha256(content).hexdigest()
        require(inventory(root) == tree, 'Final file verification failed.')
    except Exception:
        print('Migration did not finish. Do not build this project yet. The complete original is at ' +
              str(backup / root.name) + '; preserve both copies for recovery.', file=sys.stderr)
        raise
    print('Verified: every original file preserved; only project metadata and ignore rules updated.')
    print('Open: ' + str(root / target_name))
    print('Keep the private backup outside source control. Old tracked paths may appear deleted in git;')
    print('their contents still exist at the new locations (states/cache/exports are now ignored).')


parser = argparse.ArgumentParser(prog='migrate_project.sh',
    description='Safely migrate one SMEDIT project (preview by default). Close SMEDIT before --apply.')
parser.add_argument('project', help='Path to the authoritative root .smedit file; renamed to match its folder')
parser.add_argument('--apply', action='store_true', help='Back up, then apply the previewed migration')
parser.add_argument('--backup-dir', default=str(Path.home() / '.smedit' / 'backups'),
                    help='Backup parent directory, outside the project (default: ~/.smedit/backups)')
try:
    migrate(parser.parse_args())
except (OSError, ValueError) as error:
    print('ERROR: ' + str(error), file=sys.stderr)
    sys.exit(1)
PY

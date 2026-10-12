"""Synthetic fixtures only: python3 -m unittest discover -s tools/tests -v"""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid


SCRIPT = Path(__file__).resolve().parents[1] / 'migrate_project.sh'


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='smedit-migration-test-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'New Project Name'
        self.root.mkdir()
        self.project = self.root / 'Old Project Name.smedit'
        self.backups = self.base / 'backups'
        self.original = {
            'romPath': str(self.root / 'Super Metroid [JU].smc'),
            'projectFormatVersion': 5,
            'asmWorkspace': {'enabled': True, 'buildMode': 'ASM_SOURCE'},
            'rooms': {'91F8': {'custom': [1, 2, 3]}},
            'unknownFutureField': 'Keep me! 日本語',
        }
        self.project.write_text(json.dumps(self.original), encoding='utf-8')
        self.write('Super Metroid [JU].smc', b'R' * 0x8000)

    def write(self, name, contents):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(contents)
        return path

    def snapshot(self, root=None):
        root = root or self.root
        return {p.relative_to(root).as_posix(): None if p.is_dir() else p.read_bytes()
                for p in root.rglob('*')}

    def run_script(self, *flags, project=None, success=True):
        result = subprocess.run(
            ['bash', str(SCRIPT), '--backup-dir', str(self.backups), *flags, str(project or self.project)],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)
        return result

    def test_preview_makes_no_writes_including_backups(self):
        self.write('Old Project Name_states/slot_0.state', b'save')
        before = self.snapshot()
        result = self.run_script()
        self.assertIn('Preview only', result.stdout)
        self.assertIn('Old Project Name.smedit -> New Project Name.smedit', result.stdout)
        self.assertEqual(before, self.snapshot())
        self.assertFalse(self.backups.exists())

    def test_full_migration_backup_metadata_authored_files_and_idempotence(self):
        support = 'Old Project Name_smedit/'
        files = {
            'Old Project Name_states/slot_0.state': b'save',
            support + 'asm/modules/.order': b'demo.asm\n',
            support + 'asm/modules/.disabled': b'demo.asm\n',
            support + 'asm/modules/demo.asm': b'; custom ASM\n',
            support + 'asm/overrides/src/bank_90.asm': b'; custom bank\n',
            support + 'asm/original/data/private.bin': b'private',
            support + 'asm/workspace/src/main.asm': b'main',
            support + 'asm/.smedit-project-asm.properties': b'reference=preserve\n',
            support + 'exports/Landing.png': b'first mapshot',
            support + 'Landing.png': b'other mapshot',
            support + 'cre.png': b'cre preview',
            support + 'tileset_0_ure_edited.png': b'custom painting',
            support + 'music.spc': b'music',
            support + 'Old Project Name.smedit': b'{"distinct":"history"}',
            support + 'Old Project Name_v1.smc': b'historical ROM',
            support + 'unknown.txt': b'preserve unknown file',
            '.gitignore': b'custom-rule\n!states/\n',
        }
        for path, content in files.items():
            self.write(path, content)
        (self.root / support / 'asm/build/empty').mkdir(parents=True)
        before = self.snapshot()
        old_id = str(uuid.UUID(bytes=hashlib.md5(str(self.project.resolve()).encode()).digest(), version=3))
        result = self.run_script('--apply')
        target = self.root / 'New Project Name.smedit'
        self.assertTrue(target.is_file())
        self.assertFalse(self.project.exists())
        self.assertIn('Verified:', result.stdout)
        migrated = json.loads(target.read_text())
        self.assertEqual(migrated['projectId'], old_id)
        self.assertEqual(migrated['romPath'], 'Super Metroid [JU].smc')
        self.assertEqual(migrated['baseRom']['sha256'], hashlib.sha256(b'R' * 0x8000).hexdigest())
        self.assertEqual({k: v for k, v in migrated.items() if k not in ('romPath', 'projectId', 'baseRom')},
                         {k: v for k, v in self.original.items() if k != 'romPath'})
        expected_moves = {
            'states/slot_0.state': 'Old Project Name_states/slot_0.state',
            'asm/modules/.order': support + 'asm/modules/.order',
            'asm/modules/.disabled': support + 'asm/modules/.disabled',
            'asm/modules/demo.asm': support + 'asm/modules/demo.asm',
            'asm/overrides/src/bank_90.asm': support + 'asm/overrides/src/bank_90.asm',
            'smedit/asm/original/data/private.bin': support + 'asm/original/data/private.bin',
            'smedit/asm/workspace/src/main.asm': support + 'asm/workspace/src/main.asm',
            'smedit/asm/.smedit-project-asm.properties': support + 'asm/.smedit-project-asm.properties',
            'exports/Landing.png': support + 'exports/Landing.png',
            'exports/mapshots/Landing.png': support + 'Landing.png',
            'exports/graphics/cre.png': support + 'cre.png',
            'data/legacy-assets/tileset_0_ure_edited.png': support + 'tileset_0_ure_edited.png',
            'exports/audio/music.spc': support + 'music.spc',
            'archive/legacy-project/Old Project Name.smedit': support + 'Old Project Name.smedit',
            'archive/legacy-project/Old Project Name_v1.smc': support + 'Old Project Name_v1.smc',
            'smedit/unknown.txt': support + 'unknown.txt',
        }
        for path, old in expected_moves.items():
            self.assertEqual((self.root / path).read_bytes(), files[old], path)
        self.assertTrue((self.root / 'smedit/asm/build/empty').is_dir())
        self.assertTrue((self.root / '.gitignore').read_bytes().startswith(files['.gitignore']))
        backup = next(self.backups.iterdir())
        self.assertEqual(self.snapshot(backup / self.root.name), before)
        self.assertTrue((backup / 'migration.json').is_file())
        after = self.snapshot()
        again = self.run_script('--apply', project=target)
        self.assertIn('Already migrated', again.stdout)
        self.assertEqual(after, self.snapshot())
        self.assertEqual(len(list(self.backups.iterdir())), 1)

    def test_earlier_hidden_cache_and_emu_states_layout(self):
        self.write('.smedit/asm/modules/example.asm', b'ASM')
        self.write('emu_states/battery.srm', b'SRAM')
        self.run_script('--apply')
        self.assertEqual((self.root / 'asm/modules/example.asm').read_bytes(), b'ASM')
        self.assertEqual((self.root / 'states/battery.srm').read_bytes(), b'SRAM')

    def test_rom_named_sidecars_and_headerless_fingerprint(self):
        self.write('Super Metroid [JU]_states/battery.srm', b'SRAM')
        self.write('Super Metroid [JU].smc', b'H' * 512 + b'R' * 0x8000)
        self.original['projectId'] = 'existing-stable-id'
        self.original['baseRom'] = {'originalFileName': 'original.sfc',
                                    'sha256': hashlib.sha256(b'R' * 0x8000).hexdigest(), 'size': 0x8000}
        self.project.write_text(json.dumps(self.original))
        self.run_script('--apply')
        project = json.loads((self.root / 'New Project Name.smedit').read_text())
        self.assertEqual(project['baseRom'], self.original['baseRom'])
        self.assertEqual(project['projectId'], 'existing-stable-id')
        self.assertTrue((self.root / 'states/battery.srm').exists())

    def test_missing_rom_does_not_invent_identity_or_substitute_another_rom(self):
        self.original['romPath'] = 'C:\\old\\Missing Base.sfc'
        self.project.write_text(json.dumps(self.original))
        result = self.run_script('--apply')
        project = json.loads((self.root / 'New Project Name.smedit').read_text())
        self.assertEqual(project['romPath'], 'Missing Base.sfc')
        self.assertNotIn('baseRom', project)
        self.assertIn('locate/import', result.stdout)

    def test_conflicts_abort_before_backup_or_any_project_writes(self):
        cases = [
            ('Old Project Name_states/save', 'states/save'),
            ('Old Project Name_smedit/cache', 'smedit/cache'),
            ('Old Project Name_smedit/asm/modules/a.asm', 'asm/modules/a.asm'),
            ('Old Project Name_smedit/exports/preview.png', 'exports/preview.png'),
        ]
        for old, new in cases:
            with self.subTest(old=old):
                with tempfile.TemporaryDirectory(dir=str(self.base)) as separate:
                    original_root, original_project = self.root, self.project
                    try:
                        self.root = Path(separate) / 'Old Project Name'
                        self.root.mkdir()
                        self.project = self.root / 'Old Project Name.smedit'
                        self.project.write_text(json.dumps({'romPath': 'missing.smc'}))
                        self.write(old, b'old')
                        self.write(new, b'new')
                        before = self.snapshot()
                        self.assertIn('no automatic merging', self.run_script('--apply', success=False).stderr)
                        self.assertEqual(self.snapshot(), before)
                        self.assertFalse(self.backups.exists())
                    finally:
                        self.root, self.project = original_root, original_project

    def test_fingerprint_mismatch_refused(self):
        self.original['baseRom'] = {'originalFileName': 'base.smc', 'sha256': 'wrong', 'size': 0x8000}
        self.project.write_text(json.dumps(self.original))
        before = self.snapshot()
        self.assertIn('fingerprint mismatch', self.run_script('--apply', success=False).stderr)
        self.assertEqual(before, self.snapshot())

    def test_multiple_projects_refused(self):
        self.write('Other.smedit', b'{}')
        self.assertIn('Multiple root', self.run_script('--apply', success=False).stderr)
        self.assertFalse(self.backups.exists())

    def test_symlinks_refused(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (self.root / 'Old Project Name_states').symlink_to(outside, target_is_directory=True)
        self.assertIn('Symlink', self.run_script('--apply', success=False).stderr)
        self.assertEqual(list(outside.iterdir()), [])
        self.assertFalse(self.backups.exists())

    def test_invalid_json_refused(self):
        for content in ('not JSON', '{"romPath":"a.smc","romPath":"b.smc"}'):
            self.project.write_text(content)
            self.run_script('--apply', success=False)
            self.assertEqual(self.project.read_text(), content)
            self.assertFalse(self.backups.exists())

    def test_backup_inside_project_refused(self):
        self.backups = self.root / 'backups'
        before = self.snapshot()
        self.assertIn('outside the project', self.run_script('--apply', success=False).stderr)
        self.assertEqual(self.snapshot(), before)

    def test_nested_historical_project_refused(self):
        nested = self.write('Old Project Name_smedit/Old Project Name.smedit',
                            json.dumps(self.original).encode())
        before = self.snapshot()
        self.assertIn('authoritative root', self.run_script('--apply', project=nested, success=False).stderr)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.backups.exists())

    def test_different_external_base_is_not_silently_replaced(self):
        external = self.base / 'Super Metroid [JU].smc'
        external.write_bytes(b'different ROM')
        self.original['romPath'] = str(external)
        self.project.write_text(json.dumps(self.original))
        before = self.snapshot()
        self.assertIn('Recorded ROM differs', self.run_script('--apply', success=False).stderr)
        self.assertEqual(before, self.snapshot())
        self.assertFalse(self.backups.exists())


if __name__ == '__main__':
    unittest.main()

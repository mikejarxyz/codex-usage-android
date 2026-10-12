"""Regression checks for bounded ELF reads and retained page-alignment gates."""
import pathlib, struct, subprocess, os
root = pathlib.Path(__file__).resolve().parents[1]
work = root / '.probe/elf-verifier-check'
binary = work / '.probe/packaged-native/arm64-v8a/test.so'
binary.parent.mkdir(parents=True, exist_ok=True)
(work / 'settings.gradle').write_text("rootProject.name='ElfVerifierCheck'\n")
(work / 'build.gradle').write_text("tasks.register('preBuild')\napply from: '" + (root / 'scripts/verify-native.gradle').as_posix() + "'\n")
def fixture(alignment=16384, relro=16384, size=3*1024**3):
    header = bytearray(64)
    header[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', header, 32, 64)
    struct.pack_into('<HH', header, 54, 56, 2)
    load = struct.pack('<IIQQQQQQ', 1, 4, 0, 0, 0, 64, 64, alignment)
    guard = struct.pack('<IIQQQQQQ', 0x6474e552, 4, 0, 0, 0, 0, relro, 1)
    with binary.open('wb') as output:
        output.write(header + load + guard)
        output.truncate(size)
def run(expected):
    result = subprocess.run([str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), '-p', str(work), 'verifyNativePageSizes',
        '-Dorg.gradle.jvmargs=-Xmx256m', '--console=plain'],capture_output=True,text=True)
    text=result.stdout+result.stderr
    if expected == 'pass': assert result.returncode == 0 and '16 KB LOAD and RELRO checks passed' in text, text[-3000:]
    else: assert result.returncode != 0 and expected in text, text[-3000:]
try:
    fixture(); run('pass')
    fixture(alignment=4096); run('LOAD segment is not 16 KB compatible')
    fixture(relro=4096); run('RELRO end is not 16 KB aligned')
    fixture(size=64); run('invalid ELF program headers')
    print('3 GB ELF with 256 MB heap passed; incompatible LOAD, RELRO, and truncated headers rejected.')
finally:
    binary.unlink(missing_ok=True)

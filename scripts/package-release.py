"""Package the source archive, executable demo and SHA-256 checksums."""
from pathlib import Path
import argparse
import hashlib
import shutil
import xml.etree.ElementTree as ET
import zipfile


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=root / "dist")
    args = parser.parse_args()
    version = ET.parse(root / "pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version")
    demo = root / "poolpilot-demo/target" / f"poolpilot-demo-{version}.jar"
    if not demo.is_file():
        parser.error("Demo JAR is missing. Run Maven verify first.")
    with zipfile.ZipFile(demo) as jar:
        if "BOOT-INF/classes/dev/poolpilot/demo/PoolPilotDemoApplication.class" not in jar.namelist():
            parser.error("Demo JAR is not executable. Stop the demo and run Maven verify again.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    source = output / f"poolpilot-{version}-source.zip"
    directories = (".github", ".mvn", "docs", "scripts", "benchmarks", "poolpilot-core", "poolpilot-spring-boot-starter", "poolpilot-spring-boot-admin-starter", "poolpilot-demo")
    files = [p for p in root.iterdir() if p.is_file() and p.name in {
        ".dockerignore", ".gitattributes", ".gitignore", ".npmrc", "pom.xml", "mvnw", "mvnw.cmd",
        "README.md", "README.en.md", "LICENSE", "NOTICE", "CONTRIBUTING.md", "CHANGELOG.md",
        "SECURITY.md", "Dockerfile", "package.json", "package-lock.json"
    }]
    for directory in directories:
        files.extend(p for p in (root / directory).rglob("*") if p.is_file()
                     and not any(part in {"target", "__pycache__", "data"} for part in p.relative_to(root).parts)
                     and (not directory.startswith("poolpilot-")
                          or p.relative_to(root / directory).parts[0] == "src"
                          or p.name in {"pom.xml", "README.md", "README.en.md"} and p.parent == root / directory)
                     and p.suffix not in {".log", ".pyc", ".lock", ".tmp"}
                     and not p.name.startswith(".env"))
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in sorted(files, key=lambda p: p.relative_to(root).as_posix()):
            relative = file.relative_to(root).as_posix()
            info = zipfile.ZipInfo("poolpilot/" + relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if relative == "mvnw" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, file.read_bytes())
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Source archive verification failed")
        for required in ["poolpilot/.mvn/wrapper/maven-wrapper.properties", "poolpilot/.github/workflows/ci.yml", "poolpilot/README.en.md"]:
            if required not in archive.namelist():
                raise RuntimeError("Missing release file: " + required)
    released_demo = output / demo.name
    shutil.copy2(demo, released_demo)
    lines = [hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name for p in [source, released_demo]]
    (output / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    for line in lines:
        print(line)


if __name__ == "__main__":
    main()

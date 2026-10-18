"""Check release JAR versions, defaults, checksums, isolation and Java targets."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET


API_PREFIX = "haaa/shitbot/api/"
CORE_PREFIX = "haaa/shitbot/core/"
RENDERER_PREFIX = "haaa/shitbot/renderer/"
MODULES = {
    "ShitBotApi": (API_PREFIX, 52, "haaa/shitbot/api/ShitBotApi.class", None),
    "ShitBotRenderer": (RENDERER_PREFIX, 52, "haaa/shitbot/renderer/RendererFactory.class", "META-INF/services/haaa.shitbot.api.spi.ImageTemplateEngineFactory"),
    "ShitBotSpigot": ("haaa/shitbotspigot/", 52, "haaa/shitbotspigot/ShitBotSpigot.class", "plugin.yml"),
    "ShitBotBungee": ("haaa/shitbotbungee/", 52, "haaa/shitbotbungee/ShitBotBungee.class", "bungee.yml"),
    "ShitBotVelocity": ("haaa/shitbotvelocity/", 65, "haaa/shitbotvelocity/ShitBotVelocity.class", "velocity-plugin.json"),
    "ShitBotNukkit": ("haaa/shitbotnukkit/", 61, "haaa/shitbotnukkit/ShitBotNukkit.class", "plugin.yml"),
}
PLATFORM_API_PREFIXES = ("org/bukkit/", "net/md_5/bungee/api/", "com/velocitypowered/api/", "cn/nukkit/")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_version(archive, module, version):
    metadata_prefix = f"META-INF/maven/haaa/{module}/"
    properties = {}
    for line in archive.read(metadata_prefix + "pom.properties").decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and not line.startswith(("#", "!")):
            properties[key.strip()] = value.strip()
    require(properties.get("version") == version, f"{module}: Maven metadata version does not match {version}")
    require(properties.get("groupId") == "haaa" and properties.get("artifactId") == module,
            f"{module}: incorrect Maven coordinates")
    pom = archive.read(metadata_prefix + "pom.xml")
    require(b"${revision}" not in pom, f"{module}: published POM contains unresolved revision")
    model = ET.fromstring(pom)
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom_version = model.findtext("m:version", namespaces=namespace)
    if pom_version is None:
        pom_version = model.findtext("m:parent/m:version", namespaces=namespace)
    require(pom_version == version, f"{module}: published POM version does not match {version}")


def yaml_scalar(contents, key):
    # Plugin descriptors use one-line top-level scalars; no general YAML parser is needed.
    match = re.search(rf"^{re.escape(key)}:[ \t]*([^\r\n]+)", contents, re.MULTILINE)
    require(match is not None, f"Missing descriptor field {key}")
    value = match.group(1).strip()
    if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
        value = value[1:-1]
    return value


def check_platform_defaults(archive, entries, module):
    defaults = {}
    for resource in ("config.yml", "commands.yml"):
        require(entries.count(resource) == 1, f"{module}: expected one bundled {resource}")
        defaults[resource] = archive.read(resource).decode("utf-8")
        require(defaults[resource].strip(), f"{module}: empty {resource}")
        require(not re.search(r"\$\{[^}\r\n]+\}", defaults[resource]),
                f"{module}: unresolved resource filtering in {resource}")
    config = defaults["config.yml"]
    commands = defaults["commands.yml"]
    nukkit = module == "ShitBotNukkit"
    for contents, key, expected in ((config, "bstats", not nukkit),
                                   (config, "deployment", module == "ShitBotSpigot"),
                                   (commands, "backend-transport", not nukkit)):
        count = len(re.findall(rf"^{re.escape(key)}:", contents, re.MULTILINE))
        require(count == int(expected), f"{module}: incorrect default section {key}")
    scan = re.findall(r"^    scan-mod-jars: (true|false)[ \t]*\r?$", config, re.MULTILINE)
    require(scan == ["false" if nukkit else "true"], f"{module}: incorrect Mod JAR scanning default")


def check(directory, version):
    jars = sorted(directory.glob("*.jar"))
    require(len(jars) == len(MODULES), f"Expected six release JARs, found {len(jars)}")
    for module, (prefix, major, entrypoint, descriptor) in MODULES.items():
        jar = directory / f"{module}-{version}.jar"
        require(jar in jars, f"Missing expected artifact {jar.name}")
        checksum_file = jar.with_name(jar.name + ".sha256")
        checksum = checksum_file.read_text(encoding="utf-8").split()
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        require(len(checksum) == 2 and checksum[0] == digest and checksum[1] == jar.name,
                f"Invalid SHA-256 file for {jar.name}")
        with zipfile.ZipFile(jar) as archive:
            entries = archive.namelist()
            check_version(archive, module, version)
            require(entrypoint in entries, f"{jar.name}: missing {entrypoint}")
            if descriptor:
                require(descriptor in entries, f"{jar.name}: missing {descriptor}")
                require(b"${" not in archive.read(descriptor), f"{jar.name}: unfiltered plugin metadata")
                if module not in ("ShitBotApi", "ShitBotRenderer"):
                    metadata = archive.read(descriptor).decode("utf-8")
                    descriptor_version = (json.loads(metadata).get("version") if descriptor.endswith(".json")
                                          else yaml_scalar(metadata, "version"))
                    require(descriptor_version == version, f"{jar.name}: plugin version does not match {version}")
            for name in entries:
                if not name.endswith(".class") or name.startswith("META-INF/versions/"):
                    continue
                require(not name.startswith(("org/junit/", "junit/", "org/hamcrest/")),
                        f"{jar.name}: test dependency was bundled: {name}")
                require(not name.startswith(PLATFORM_API_PREFIXES),
                        f"{jar.name}: provided platform API was bundled: {name}")
                # Check project classes; relocated third-party libraries have their own targets.
                if name.startswith((API_PREFIX, CORE_PREFIX)):
                    expected = 52
                elif name.startswith(prefix) and "/lib/" not in name:
                    expected = major
                else:
                    continue
                header = archive.read(name)[:8]
                require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", f"Invalid class file: {name}")
                actual = struct.unpack(">H", header[6:8])[0]
                require(actual == expected, f"{jar.name}: {name} has class version {actual}, expected {expected}")
            if module == "ShitBotApi":
                require(not any(name.startswith((CORE_PREFIX, RENDERER_PREFIX)) for name in entries),
                        "Public API JAR contains implementation classes")
            elif module == "ShitBotRenderer":
                require(not any(name.startswith((API_PREFIX, CORE_PREFIX)) for name in entries),
                        "Renderer must use the host API and remain independent of Core")
                renderer_version = archive.read("META-INF/shitbot-renderer.version").decode("utf-8").strip()
                require(renderer_version == version, f"Renderer version resource does not match {version}")
                require(archive.read(descriptor).decode("utf-8").strip() == "haaa.shitbot.renderer.RendererFactory",
                        "Renderer SPI factory is missing or incorrect")
            else:
                check_platform_defaults(archive, entries, module)
                for resource in ("haaa/shitbot/core/runtime/ShitBotRuntime.class", "haaa/shitbot/api/ShitBotApi.class",
                                 "lang/zh_CN.yml", "lang/en_US.yml", "templates/default.yml",
                                 "haaa/shitbot/core/update/update-public-key.pem"):
                    require(resource in entries, f"{jar.name}: missing shared resource {resource}")
                require(not any(name.startswith(RENDERER_PREFIX) for name in entries),
                        f"{jar.name}: optional renderer was bundled into the platform plugin")
        print(f"Verified {jar.name}: version {version}, SHA-256, contents and Java {major - 44} project classes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--version", required=True, help="Expected Maven revision, without a release tag prefix")
    args = parser.parse_args()
    try:
        check(args.directory, args.version)
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Release artifact check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

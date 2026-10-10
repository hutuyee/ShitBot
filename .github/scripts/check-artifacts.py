"""Check the six release JARs, their checksums, isolation boundaries and Java targets."""

import argparse
import hashlib
from pathlib import Path
import struct
import sys
import zipfile


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


def check(directory):
    jars = sorted(directory.glob("*.jar"))
    require(len(jars) == len(MODULES), f"Expected six release JARs, found {len(jars)}")
    for module, (prefix, major, entrypoint, descriptor) in MODULES.items():
        candidates = [jar for jar in jars if jar.name.startswith(module + "-")]
        require(len(candidates) == 1, f"Expected exactly one JAR for {module}")
        jar = candidates[0]
        checksum_file = jar.with_name(jar.name + ".sha256")
        checksum = checksum_file.read_text(encoding="utf-8").split()
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        require(len(checksum) == 2 and checksum[0] == digest and checksum[1] == jar.name,
                f"Invalid SHA-256 file for {jar.name}")
        with zipfile.ZipFile(jar) as archive:
            entries = archive.namelist()
            require(entrypoint in entries, f"{jar.name}: missing {entrypoint}")
            if descriptor:
                require(descriptor in entries, f"{jar.name}: missing {descriptor}")
                require(b"${" not in archive.read(descriptor), f"{jar.name}: unfiltered plugin metadata")
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
                version = archive.read("META-INF/shitbot-renderer.version").strip()
                require(version and b"${" not in version, "Renderer version resource was not filtered")
                require(archive.read(descriptor).decode("utf-8").strip() == "haaa.shitbot.renderer.RendererFactory",
                        "Renderer SPI factory is missing or incorrect")
            else:
                for resource in ("haaa/shitbot/core/runtime/ShitBotRuntime.class", "haaa/shitbot/api/ShitBotApi.class",
                                 "lang/zh_CN.yml", "lang/en_US.yml", "templates/default.yml",
                                 "haaa/shitbot/core/update/update-public-key.pem"):
                    require(resource in entries, f"{jar.name}: missing shared resource {resource}")
                require(not any(name.startswith(RENDERER_PREFIX) for name in entries),
                        f"{jar.name}: optional renderer was bundled into the platform plugin")
        print(f"Verified {jar.name}: SHA-256, contents and Java {major - 44} project classes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        check(args.directory)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release artifact check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Resolve the CI revision and reject release tags that disagree with the source."""

import os
from pathlib import Path
import re
import shlex
import sys


def main():
    try:
        arguments = shlex.split(Path(".mvn/maven.config").read_text(encoding="utf-8"), comments=True)
        revisions = [argument.partition("=")[2] for argument in arguments if argument.startswith("-Drevision=")]
        if len(revisions) != 1 or not re.fullmatch(r"[0-9][A-Za-z0-9._+-]*", revisions[0]):
            raise ValueError(".mvn/maven.config must define exactly one -Drevision=<version>.")
        version = revisions[0]
        tag = os.environ.get("RELEASE_TAG", "")
        tag_version = tag[1:] if tag.startswith(("v", "V")) else tag
        if tag and tag_version != version:
            raise ValueError(f"Release tag {tag!r} does not match revision {version!r}; update .mvn/maven.config before tagging.")
        output_path = os.environ.get("GITHUB_OUTPUT")
        if output_path:
            with open(output_path, "a", encoding="utf-8") as output:
                output.write(f"version={version}\n")
        print(f"Build version: {version}")
        return 0
    except (OSError, ValueError) as error:
        print(f"Version check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

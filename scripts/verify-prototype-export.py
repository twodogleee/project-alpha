#!/usr/bin/env python3
"""验证并安全解压实际交付 ZIP，可对比服务器封存对象；不会运行其中代码。@author Andersen"""
import argparse
import hashlib
import json
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath


def digest(content):
    """与后端一致的文件内容摘要。"""
    return hashlib.sha256(content).hexdigest()


def verify(archive, destination, artifact_root=None):
    """严格检查完整清单、ZIP 安全属性与固定对象，然后创建新的独占解压目录。"""
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size > 80 * 1024 * 1024:
        raise ValueError("ZIP 无效或超过 80 MiB")
    if destination.exists() or destination.is_symlink():
        raise ValueError("解压目标必须尚不存在，避免覆盖文件")
    files, total = {}, 0
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        if not entries or len(entries) > 3000:
            raise ValueError("ZIP 文件数量无效")
        for entry in entries:
            name = entry.filename
            parts = PurePosixPath(name).parts
            if (not parts or name.startswith("/") or "\\" in name or ":" in name
                    or name != "/".join(parts) or any(part.startswith(".") or part.endswith((".", " ")) for part in parts)
                    or any(ord(char) < 32 for char in name)
                    or any(part.lower() in {"node_modules", "credentials", ".aws", ".codex"} for part in parts)
                    or re.search(r"\.(pem|key|p12|pfx|jks|keystore|map)$", name, re.IGNORECASE)
                    or entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1
                    or name in files):
                raise ValueError("ZIP 包含不安全、重复或私有条目")
            total += entry.file_size
            if entry.file_size < 0 or total > 80 * 1024 * 1024:
                raise ValueError("ZIP 解压后超过大小限制")
            with zipped.open(entry) as stream:
                content = stream.read(entry.file_size + 1)
                if len(content) != entry.file_size:
                    raise ValueError("ZIP 文件长度不一致")
                files[name] = content
    manifest = json.loads(files["export-manifest.json"])
    if manifest["schemaVersion"] != 1 or manifest["format"] not in {"source", "static"}:
        raise ValueError("交付清单协议无效")
    declared = {}
    for entry in manifest["files"]:
        name = entry["path"]
        if name in declared or name == "export-manifest.json":
            raise ValueError("交付清单重复或自循环")
        content = files[name]
        if entry["bytes"] != len(content) or entry["sha256"] != digest(content):
            raise ValueError("交付文件摘要不一致")
        declared[name] = entry
    if set(declared) != set(files) - {"export-manifest.json"}:
        raise ValueError("ZIP 存在清单外文件")
    prefix = "source/" if manifest["format"] == "source" else "dist/"
    if artifact_root:
        key = manifest["objectKey"]
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            raise ValueError("对象身份无效")
        sealed = artifact_root / key
        original = (sealed / "artifact.json").read_bytes()
        if digest(original) != key:
            raise ValueError("封存对象身份不一致")
        artifact = json.loads(original)
        expected = artifact["sources" if manifest["format"] == "source" else "files"]
        if {prefix + entry["path"] for entry in expected} != {name for name in files if name.startswith(prefix)}:
            raise ValueError("交付目录与该修订封存文件集合不一致")
        for entry in expected:
            content = files[prefix + entry["path"]]
            if entry["bytes"] != len(content) or entry["sha256"] != digest(content) or content != (sealed / prefix / entry["path"]).read_bytes():
                raise ValueError("交付文件与该修订封存字节不一致")
        for name, content in files.items():
            if name.startswith("provenance/") and content != (sealed / "source" / name.removeprefix("provenance/")).read_bytes():
                raise ValueError("归属材料与封存源码不一致")
    destination.mkdir(parents=True, exist_ok=False)
    for name, content in files.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(content)
    return {"status": "PASSED", "format": manifest["format"], "revisionId": manifest["revisionId"],
            "mode": manifest["mode"], "objectKey": manifest["objectKey"], "files": len(files),
            "bytes": total, "frozenBytesCompared": artifact_root is not None,
            "destination": str(destination.resolve())}


def main():
    """CLI 只接受待验证 ZIP、新目标目录及可选封存根，后续重建由包内说明执行。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--artifact-root", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.destination, args.artifact_root), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

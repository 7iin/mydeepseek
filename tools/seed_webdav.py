#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「导出所有数据」的备份文件，转成网盘同步目录结构 —— 供手动上传用。

## 为什么需要它

app 的 WebDAV 同步不是"传一个备份文件"，而是往网盘写一个目录：

    myDeepSeek/
    ├─ meta.json                       format / version / 预设
    ├─ conv/<id>.<updatedAt>.json       一个会话一个文件，文件名自带版本
    └─ media/<sha256前32位>.<ext>       图片，内容寻址

首次全量同步要在手机上发几百个请求（会话 + 每张图），很容易被网盘的
**请求频率风控**掐断（坚果云 503）。而这个目录结构是纯静态文件：
用电脑的网盘客户端 / 网页版一次性拖上去，**零 API 请求**，不会被限流。

传完之后 app 再同步，会看到"远端已经有这一版"，于是只发几个请求就结束。

## 用法

    python seed_webdav.py 备份.zip -o myDeepSeek
    python seed_webdav.py backup.json -o myDeepSeek

然后把生成的 `myDeepSeek` 整个目录放到网盘的同步根下（地址填的什么就放哪）。
只依赖标准库，Python 3.8+。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import zipfile
from typing import Any, Dict, Iterable, List, Tuple

# Windows 控制台默认 GBK，打印中文和路径会炸
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

SYNC_FORMAT = "mydeepseek-sync"
SYNC_VERSION = 1
BACKUP_FORMAT = "simplechat-backup"

# 与 Kotlin 侧 AttachmentFiles.extensionOf 保持一致：
# 名字由（内容 + 扩展名）决定，两边算出来必须一模一样，否则会重复上传。
EXT_BY_MIME = {
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
}


def ext_of(mime: str) -> str:
    return EXT_BY_MIME.get((mime or "").lower(), ".jpg")


def short_hash(raw: bytes) -> str:
    """sha256 的前 32 个十六进制字符 —— 与 Kotlin 的 take(32) 一致。"""
    return hashlib.sha256(raw).hexdigest()[:32]


def iter_json_docs(path: str) -> Iterable[Tuple[str, Any]]:
    """从一个 .json 或 .zip 里产出 (来源名, 解析后的 JSON)。"""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                if info.is_dir() or not info.filename.lower().endswith(".json"):
                    continue
                try:
                    with zf.open(info) as fp:
                        yield info.filename, json.loads(fp.read().decode("utf-8"))
                except Exception as exc:  # 坏文件/非 UTF-8：跳过，不中断整包
                    print(f"  跳过 {info.filename}：{exc}")
        return

    with open(path, "rb") as fp:
        yield os.path.basename(path), json.loads(fp.read().decode("utf-8"))


def find_backup(path: str) -> Tuple[str, Dict[str, Any]]:
    """在输入里找出本应用的备份文档（按 format 字段认）。"""
    candidates: List[str] = []
    for name, doc in iter_json_docs(path):
        if isinstance(doc, dict) and doc.get("format") == BACKUP_FORMAT:
            return name, doc
        candidates.append(name)
    raise SystemExit(
        "没找到备份文档（需要 format == "
        f"'{BACKUP_FORMAT}'）。看过的文件：{', '.join(candidates) or '（空）'}"
    )


def fill_required(conversation: Dict[str, Any]) -> None:
    """
    补齐 app 数据类里**没有默认值**的字段。

    这些字段在老备份里可能不存在（它们后来才加），而 kotlinx 反序列化遇到
    缺失的必填字段会直接抛 `MissingFieldException` —— 整条会话都读不出来。
    备份本身是什么样就传什么样更"保真"，但**读不出来的保真没有意义**：
    缺失时用会话的时间兜底，比让 app 静默当成"远端空"再重传一遍全库好得多。
    """
    created = conversation.get("createdAt") or 0
    for message in conversation.get("messages") or []:
        if not isinstance(message.get("createdAt"), int):
            message["createdAt"] = created


def fill_required(conversation: Dict[str, Any]) -> None:
    """
    补齐 app 数据类里**没有默认值**的字段。

    这些字段在老备份里可能不存在（它们后来才加），而 kotlinx 反序列化遇到
    缺失的必填字段会直接抛 `MissingFieldException` —— 整条会话都读不出来。
    备份本身是什么样就传什么样更"保真"，但**读不出来的保真没有意义**：
    缺失时用会话的时间兜底，比让 app 静默当成"远端空"再重传一遍全库好得多。
    """
    created = conversation.get("createdAt") or 0
    for message in conversation.get("messages") or []:
        if not isinstance(message.get("createdAt"), int):
            message["createdAt"] = created


def externalize_images(
    conversation: Dict[str, Any], media_dir: str
) -> Tuple[Dict[str, Any], int, int]:
    """
    把会话里内联的 base64 图片写成 media/ 下的独立文件。

    返回 (改写后的会话, 图片张数, 缺失张数)。

    JSON 里的附件直接改 `path` / `data` 两个键：其余字段原样保留，
    这样不依赖 Python 猜 Kotlin 数据类的默认值 —— 备份里有什么就传什么。
    """
    written = 0
    missing = 0

    for message in conversation.get("messages") or []:
        for attachment in message.get("attachments") or []:
            if attachment.get("kind") != "IMAGE":
                continue  # 文本附件本来就小，保持内联

            data = attachment.get("data") or ""
            if not data:
                # 备份里没有内联字节（可能是"仅路径"导出，或原件已丢失）。
                # 保持原样：app 会把它当成"无图"，不会传坏引用。
                missing += 1
                continue

            try:
                raw = base64.b64decode(data, validate=False)
            except Exception:
                missing += 1
                continue

            name = short_hash(raw) + ext_of(attachment.get("mime") or "")
            target = os.path.join(media_dir, name)
            if not os.path.exists(target):
                with open(target, "wb") as fp:
                    fp.write(raw)
                written += 1

            attachment["data"] = ""
            attachment["path"] = name

    return conversation, written, missing


def convert(src: str, out_dir: str) -> int:
    source_name, doc = find_backup(src)
    conversations = doc.get("conversations") or []
    presets = doc.get("presets") or []
    print(f"备份来源：{source_name}")
    print(f"会话 {len(conversations)} 个 · 预设 {len(presets)} 个")

    conv_dir = os.path.join(out_dir, "conv")
    media_dir = os.path.join(out_dir, "media")
    os.makedirs(conv_dir, exist_ok=True)
    os.makedirs(media_dir, exist_ok=True)

    total_images = 0
    total_missing = 0
    seen_ids = {}

    for conversation in conversations:
        conv_id = conversation.get("id")
        updated_at = conversation.get("updatedAt")
        if not conv_id or not isinstance(updated_at, int):
            print(f"  跳过一个缺 id/updatedAt 的会话：{conv_id}")
            continue
        if conv_id in seen_ids:
            print(f"  跳过重复会话 {conv_id}")
            continue
        seen_ids[conv_id] = True

        fill_required(conversation)
        fill_required(conversation)
        conversation, written, missing = externalize_images(conversation, media_dir)
        total_images += written
        total_missing += missing

        # 文件名必须和 Kotlin 的 SyncLayout.conversationName 完全一致
        name = f"{conv_id}.{updated_at}.json"
        with open(os.path.join(conv_dir, name), "w", encoding="utf-8", newline="\n") as fp:
            json.dump(conversation, fp, ensure_ascii=False, separators=(",", ":"))

    for preset in presets:
        preset.setdefault("sortOrder", 0)
        preset.setdefault("createdAt", 0)
        preset.setdefault("updatedAt", 0)

    for preset in presets:
        preset.setdefault("sortOrder", 0)
        preset.setdefault("createdAt", 0)
        preset.setdefault("updatedAt", 0)

    meta = {
        "format": SYNC_FORMAT,
        "version": SYNC_VERSION,
        "exportedAt": doc.get("exportedAt") or 0,
        "presets": presets,
    }
    with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8", newline="\n") as fp:
        json.dump(meta, fp, ensure_ascii=False, separators=(",", ":"))

    size = sum(
        os.path.getsize(os.path.join(root, f))
        for root, _, files in os.walk(out_dir)
        for f in files
    )
    print()
    print(f"完成：{len(seen_ids)} 个会话，{total_images} 张图片写入 media/")
    if total_missing:
        print(f"      {total_missing} 张图片在备份里没有内联字节，已按『无图』处理")
    print(f"输出目录：{os.path.abspath(out_dir)}（{size / 1024 / 1024:.1f} MB）")
    print()
    print("下一步：把这个目录整个上传到网盘的同步位置（设置里填的『远程目录』）。")
    print("传完在 app 里点『立即同步』——两边版本一致，只会发几个请求。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="把 myDeepSeek 备份转成 WebDAV 同步目录（供手动上传，避免请求风控）",
    )
    parser.add_argument("source", help="备份文件：.zip 或 .json")
    parser.add_argument(
        "-o",
        "--out",
        default="myDeepSeek",
        help="输出目录名，应等于 app 设置里的『远程目录』（默认 myDeepSeek）",
    )
    args = parser.parse_args()

    if not os.path.exists(args.source):
        raise SystemExit(f"文件不存在：{args.source}")
    return convert(args.source, args.out)


if __name__ == "__main__":
    sys.exit(main())

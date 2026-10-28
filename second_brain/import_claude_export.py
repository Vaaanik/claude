"""Импорт экспорта данных claude.ai (Settings → Privacy → Export data) в архив на сервере.

Чаты становятся markdown-файлами, проекты — папками с инструкциями и документами,
чтобы бот мог искать по ним обычным поиском по файлам.

Запуск вручную: python import_claude_export.py export.zip [папка_архива]
"""

import json
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path


def _safe(name: str, limit: int = 80) -> str:
    name = re.sub(r"[^\w\- ]+", "", name or "").strip().replace(" ", "_")
    return name[:limit] or "untitled"


def _message_text(msg: dict) -> str:
    parts = [c.get("text", "") for c in msg.get("content") or [] if c.get("type") == "text"]
    text = "\n\n".join(p for p in parts if p) or msg.get("text", "")
    for att in msg.get("attachments") or []:
        if att.get("extracted_content"):
            text += f"\n\n[Вложение {att.get('file_name', '')}]\n{att['extracted_content']}"
    return text.strip()


def import_export(source: Path, archive: Path) -> str:
    with tempfile.TemporaryDirectory() as tmp:
        if source.is_file():
            with zipfile.ZipFile(source) as z:
                z.extractall(tmp)
            root = Path(tmp)
        else:
            root = source
        found = {p.name: p for p in root.rglob("*.json")}
        if "conversations.json" not in found:
            raise ValueError("В архиве нет conversations.json — это не экспорт claude.ai.")

        chats_dir, projects_dir = archive / "chats", archive / "projects"
        chats_dir.mkdir(parents=True, exist_ok=True)
        projects_dir.mkdir(parents=True, exist_ok=True)

        index = []
        conversations = json.loads(found["conversations.json"].read_text(encoding="utf-8"))
        for conv in conversations:
            messages = conv.get("chat_messages") or []
            if not messages:
                continue
            title = conv.get("name") or "Без названия"
            date = (conv.get("created_at") or "")[:10]
            path = chats_dir / f"{date}_{_safe(title)}_{conv.get('uuid', '')[:8]}.md"
            lines = [f"# {title}", f"Дата: {date}", ""]
            for m in messages:
                who = "Я" if m.get("sender") == "human" else "Claude"
                lines += [f"## {who} ({(m.get('created_at') or '')[:16].replace('T', ' ')})", _message_text(m), ""]
            path.write_text("\n".join(lines), encoding="utf-8")
            index.append(f"- {date} — {title} → chats/{path.name}")

        projects = []
        if "projects.json" in found:
            for proj in json.loads(found["projects.json"].read_text(encoding="utf-8")):
                pdir = projects_dir / _safe(proj.get("name"))
                pdir.mkdir(parents=True, exist_ok=True)
                info = [f"# {proj.get('name')}", "", proj.get("description") or ""]
                if proj.get("prompt_template"):
                    info += ["", "## Инструкции проекта", proj["prompt_template"]]
                (pdir / "README.md").write_text("\n".join(info), encoding="utf-8")
                for doc in proj.get("docs") or []:
                    (pdir / _safe(doc.get("filename"), 120)).write_text(doc.get("content") or "", encoding="utf-8")
                projects.append(proj.get("name"))

        # память Claude и прочие файлы экспорта сохраняем как есть
        for name, p in found.items():
            if name not in ("conversations.json", "projects.json"):
                shutil.copy(p, archive / name)

        index.sort(reverse=True)
        (archive / "index.md").write_text(
            "# Архив claude.ai\n\n## Проекты\n" + "\n".join(f"- {p}" for p in projects)
            + "\n\n## Чаты (новые сверху)\n" + "\n".join(index),
            encoding="utf-8",
        )
        extra = sorted(n for n in found if n not in ("conversations.json", "projects.json"))
        return (f"Импортировано чатов: {len(index)}, проектов: {len(projects)}"
                + (f"\nПроекты: {', '.join(projects)}" if projects else "")
                + (f"\nДругие файлы: {', '.join(extra)}" if extra else ""))


if __name__ == "__main__":
    dest = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("~/second-brain/claude-archive").expanduser()
    print(import_export(Path(sys.argv[1]), dest))

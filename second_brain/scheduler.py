"""Расписание второго мозга: разовые напоминания, повторяющиеся задачи и системные задания.

Задания хранятся в schedules.json на сервере и переживают перезапуски.
mode: remind — просто прислать текст; task — выполнить prompt через Claude и прислать результат;
backup — прислать архив памяти.
"""

import json
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from croniter import croniter


class Scheduler:
    def __init__(self, path: Path, timezone: str):
        self.path = path
        self.tz = ZoneInfo(timezone)
        self.jobs: list[dict] = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []

    def now(self) -> datetime:
        return datetime.now(self.tz)

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.jobs, ensure_ascii=False, indent=2), encoding="utf-8")

    def _next(self, job: dict, after: datetime) -> str | None:
        if job.get("cron"):
            return croniter(job["cron"], after).get_next(datetime).isoformat()
        at = datetime.fromisoformat(job["at"])
        if at.tzinfo is None:
            at = at.replace(tzinfo=self.tz)
        return at.isoformat() if at > after else None

    def add(self, *, title: str, prompt: str, mode: str, chat_id: int, thread_id: int | None,
            at: str = "", cron: str = "", voice: bool = False) -> dict:
        if mode not in ("remind", "task", "backup"):
            raise ValueError("mode должен быть remind или task")
        if bool(at) == bool(cron):
            raise ValueError("Укажите либо at (разовое), либо cron (повторяющееся)")
        if cron and not croniter.is_valid(cron):
            raise ValueError(f"Неверное cron-выражение: {cron}")
        job = {"id": uuid.uuid4().hex[:6], "title": title, "prompt": prompt, "mode": mode,
               "chat_id": chat_id, "thread_id": thread_id, "at": at or None, "cron": cron or None,
               "voice": voice}
        job["next"] = self._next(job, self.now())
        if job["next"] is None:
            raise ValueError("Это время уже прошло")
        self.jobs.append(job)
        self.save()
        return job

    def cancel(self, job_id: str) -> bool:
        before = len(self.jobs)
        self.jobs = [j for j in self.jobs if j["id"] != job_id]
        self.save()
        return len(self.jobs) < before

    def due(self) -> list[dict]:
        """Возвращает задания, которым пора сработать, и переносит их на следующий раз."""
        now = self.now()
        ready = [j for j in self.jobs if j["next"] and datetime.fromisoformat(j["next"]) <= now]
        for job in ready:
            job["next"] = self._next(job, now) if job.get("cron") else None
        if ready:
            self.jobs = [j for j in self.jobs if j["next"]]
            self.save()
        return ready

    def describe(self, chat_id: int | None = None) -> str:
        rows = []
        for j in sorted(self.jobs, key=lambda j: j["next"]):
            if chat_id is not None and j["chat_id"] != chat_id:
                continue
            when = f"повтор: {j['cron']}" if j.get("cron") else "разово"
            nxt = datetime.fromisoformat(j["next"]).strftime("%d.%m %H:%M")
            rows.append(f"- [{j['id']}] {j['title']} — {when}, следующий раз {nxt} ({j['mode']})")
        return "\n".join(rows) or "Расписание пустое."

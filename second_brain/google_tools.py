"""Gmail, Google Календарь и Google Диск как инструменты для Claude.

Подключение: /google в боте → ссылка на вход в Google → после входа браузер откроет
http://localhost/?code=... (страница не загрузится — так и задумано) → эту ссылку
пересылаете боту, он сохраняет доступ в google_token.json на сервере.
"""

import asyncio
import base64
import os
import re
from datetime import datetime, timedelta
from email.message import EmailMessage
from html import unescape
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

from claude_agent_sdk import create_sdk_mcp_server, tool

SCOPES = [
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/drive.readonly",
]
TOKEN_FILE = Path(__file__).with_name("google_token.json")
TIMEZONE = os.environ.get("TIMEZONE", "Europe/Kyiv")
REDIRECT_URI = "http://localhost"

# Только чтение — без подтверждения. Отправка писем и создание встреч требуют кнопки «Разрешить».
SAFE_GOOGLE_TOOLS = {
    "mcp__google__gmail_search", "mcp__google__gmail_read", "mcp__google__gmail_draft",
    "mcp__google__calendar_events", "mcp__google__drive_search", "mcp__google__drive_read",
}

_pending_flow = None


def configured() -> bool:
    return bool(os.environ.get("GOOGLE_CLIENT_ID") and os.environ.get("GOOGLE_CLIENT_SECRET"))


def connected() -> bool:
    return TOKEN_FILE.exists()


def _flow():
    from google_auth_oauthlib.flow import Flow
    config = {"installed": {
        "client_id": os.environ["GOOGLE_CLIENT_ID"],
        "client_secret": os.environ["GOOGLE_CLIENT_SECRET"],
        "auth_uri": "https://accounts.google.com/o/oauth2/auth",
        "token_uri": "https://oauth2.googleapis.com/token",
        "redirect_uris": [REDIRECT_URI],
    }}
    return Flow.from_client_config(config, SCOPES, redirect_uri=REDIRECT_URI)


def auth_url() -> str:
    global _pending_flow
    _pending_flow = _flow()
    url, _ = _pending_flow.authorization_url(access_type="offline", prompt="consent")
    return url


def is_auth_redirect(text: str) -> bool:
    return text.strip().startswith(REDIRECT_URI) and "code=" in text


def finish_auth(redirect_url: str) -> None:
    if _pending_flow is None:
        raise RuntimeError("Сначала отправьте /google и откройте новую ссылку.")
    code = parse_qs(urlparse(redirect_url.strip()).query)["code"][0]
    _pending_flow.fetch_token(code=code)
    TOKEN_FILE.write_text(_pending_flow.credentials.to_json(), encoding="utf-8")
    TOKEN_FILE.chmod(0o600)


def _creds():
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    if not TOKEN_FILE.exists():
        raise RuntimeError("Google не подключён. Пользователю нужно отправить боту /google.")
    creds = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPES)
    if not creds.valid:
        creds.refresh(Request())
        TOKEN_FILE.write_text(creds.to_json(), encoding="utf-8")
    return creds


def _service(name: str, version: str):
    from googleapiclient.discovery import build
    return build(name, version, credentials=_creds(), cache_discovery=False)


# ---------- Gmail ----------

def _header(msg: dict, name: str) -> str:
    for h in msg.get("payload", {}).get("headers", []):
        if h["name"].lower() == name.lower():
            return h["value"]
    return ""


def _body_text(payload: dict) -> str:
    plain, html = [], []

    def walk(part):
        data = part.get("body", {}).get("data")
        if data:
            text = base64.urlsafe_b64decode(data).decode("utf-8", "replace")
            (plain if part.get("mimeType") == "text/plain" else html if part.get("mimeType") == "text/html" else plain).append(text)
        for p in part.get("parts", []) or []:
            walk(p)

    walk(payload)
    if plain:
        return "\n".join(plain)
    text = re.sub(r"<(script|style).*?</\1>", " ", "\n".join(html), flags=re.S | re.I)
    text = re.sub(r"<br\s*/?>|</p>", "\n", text, flags=re.I)
    return re.sub(r"[ \t]+", " ", unescape(re.sub(r"<[^>]+>", " ", text))).strip()


def gmail_search(query: str, max_results: int = 10) -> str:
    gmail = _service("gmail", "v1")
    res = gmail.users().messages().list(userId="me", q=query, maxResults=max_results).execute()
    rows = []
    for m in res.get("messages", []):
        msg = gmail.users().messages().get(
            userId="me", id=m["id"], format="metadata", metadataHeaders=["From", "Subject", "Date"]
        ).execute()
        unread = "🔵 " if "UNREAD" in msg.get("labelIds", []) else ""
        rows.append(f"{unread}id={m['id']} | {_header(msg, 'Date')} | {_header(msg, 'From')} | "
                    f"{_header(msg, 'Subject')}\n  {msg.get('snippet', '')}")
    return "\n".join(rows) or "Писем не найдено."


def gmail_read(message_id: str) -> str:
    msg = _service("gmail", "v1").users().messages().get(userId="me", id=message_id, format="full").execute()
    body = _body_text(msg.get("payload", {}))
    if len(body) > 15000:
        body = body[:15000] + "\n…(обрезано)"
    return (f"От: {_header(msg, 'From')}\nКому: {_header(msg, 'To')}\nДата: {_header(msg, 'Date')}\n"
            f"Тема: {_header(msg, 'Subject')}\nthreadId: {msg.get('threadId')}\n\n{body}")


def _build_email(to: str, subject: str, body: str, reply_to_id: str | None) -> dict:
    email = EmailMessage()
    email["To"], email["Subject"] = to, subject
    email.set_content(body)
    result = {}
    if reply_to_id:
        orig = _service("gmail", "v1").users().messages().get(
            userId="me", id=reply_to_id, format="metadata", metadataHeaders=["Message-ID", "Subject"]
        ).execute()
        mid = _header(orig, "Message-ID")
        if mid:
            email["In-Reply-To"] = email["References"] = mid
        result["threadId"] = orig["threadId"]
    result["raw"] = base64.urlsafe_b64encode(email.as_bytes()).decode()
    return result


def gmail_send(to: str, subject: str, body: str, reply_to_id: str | None = None) -> str:
    sent = _service("gmail", "v1").users().messages().send(
        userId="me", body=_build_email(to, subject, body, reply_to_id)
    ).execute()
    return f"Письмо отправлено, id={sent['id']}"


def gmail_draft(to: str, subject: str, body: str, reply_to_id: str | None = None) -> str:
    draft = _service("gmail", "v1").users().drafts().create(
        userId="me", body={"message": _build_email(to, subject, body, reply_to_id)}
    ).execute()
    return f"Черновик сохранён в Gmail, id={draft['id']}"


# ---------- Календарь ----------

def calendar_events(days_ahead: int = 7, query: str | None = None, days_back: int = 0) -> str:
    tz = ZoneInfo(TIMEZONE)
    now = datetime.now(tz)
    start = (now - timedelta(days=days_back)).replace(hour=0, minute=0, second=0, microsecond=0)
    res = _service("calendar", "v3").events().list(
        calendarId="primary", timeMin=start.isoformat(), timeMax=(now + timedelta(days=days_ahead)).isoformat(),
        singleEvents=True, orderBy="startTime", q=query or None, maxResults=100,
    ).execute()
    rows = []
    for e in res.get("items", []):
        s = e["start"].get("dateTime", e["start"].get("date"))
        end = e["end"].get("dateTime", e["end"].get("date"))
        loc = f" | 📍 {e['location']}" if e.get("location") else ""
        rows.append(f"id={e['id']} | {s} → {end} | {e.get('summary', '(без названия)')}{loc}")
    return "\n".join(rows) or "Событий нет."


def calendar_create(title: str, start: str, end: str, description: str = "", location: str = "") -> str:
    event = {"summary": title, "description": description, "location": location}
    if "T" in start:
        event["start"] = {"dateTime": start, "timeZone": TIMEZONE}
        event["end"] = {"dateTime": end, "timeZone": TIMEZONE}
    else:
        event["start"], event["end"] = {"date": start}, {"date": end}
    created = _service("calendar", "v3").events().insert(calendarId="primary", body=event).execute()
    return f"Событие создано: {created.get('htmlLink')}"


def calendar_delete(event_id: str) -> str:
    _service("calendar", "v3").events().delete(calendarId="primary", eventId=event_id).execute()
    return "Событие удалено."


# ---------- Диск ----------

def drive_search(query: str, max_results: int = 10) -> str:
    q = query if any(op in query for op in ("contains", "=", " in ")) else \
        f"fullText contains '{query.replace(chr(39), ' ')}' and trashed = false"
    res = _service("drive", "v3").files().list(
        q=q, pageSize=max_results, fields="files(id,name,mimeType,modifiedTime,webViewLink)"
    ).execute()
    rows = [f"id={f['id']} | {f['name']} | {f['mimeType']} | {f['modifiedTime'][:10]} | {f.get('webViewLink', '')}"
            for f in res.get("files", [])]
    return "\n".join(rows) or "Файлов не найдено."


def drive_read(file_id: str) -> str:
    drive = _service("drive", "v3")
    meta = drive.files().get(fileId=file_id, fields="name,mimeType").execute()
    mime = meta["mimeType"]
    exports = {
        "application/vnd.google-apps.document": "text/plain",
        "application/vnd.google-apps.spreadsheet": "text/csv",
        "application/vnd.google-apps.presentation": "text/plain",
    }
    if mime in exports:
        data = drive.files().export(fileId=file_id, mimeType=exports[mime]).execute()
    elif mime.startswith("text/") or mime in ("application/json", "application/csv"):
        data = drive.files().get_media(fileId=file_id).execute()
    else:
        return f"«{meta['name']}» ({mime}) — это не текстовый файл, прочитать его содержимое не могу."
    text = data.decode("utf-8", "replace") if isinstance(data, bytes) else str(data)
    if len(text) > 30000:
        text = text[:30000] + "\n…(обрезано)"
    return f"# {meta['name']}\n\n{text}"


# ---------- MCP-сервер для Claude ----------

def _wrap(fn):
    async def run(args):
        try:
            text = await asyncio.to_thread(fn, **args)
            return {"content": [{"type": "text", "text": text}]}
        except Exception as e:
            return {"content": [{"type": "text", "text": f"Ошибка Google: {e}"}], "is_error": True}
    return run


def google_server():
    tools = [
        tool("gmail_search", "Поиск писем в Gmail. query — синтаксис поиска Gmail "
             "(например: is:unread, from:ivan, newer_than:2d, subject:счёт).",
             {"query": str, "max_results": int})(_wrap(gmail_search)),
        tool("gmail_read", "Прочитать письмо целиком по id.", {"message_id": str})(_wrap(gmail_read)),
        tool("gmail_draft", "Создать черновик письма в Gmail (не отправляет). reply_to_id — id письма, "
             "если это ответ; иначе пустая строка.",
             {"to": str, "subject": str, "body": str, "reply_to_id": str})(_wrap(gmail_draft)),
        tool("gmail_send", "Отправить письмо от имени пользователя. Используй, только если пользователь явно "
             "попросил отправить. reply_to_id — id письма, если это ответ; иначе пустая строка.",
             {"to": str, "subject": str, "body": str, "reply_to_id": str})(_wrap(gmail_send)),
        tool("calendar_events", f"События Google Календаря от начала сегодняшнего дня (минус days_back дней) "
             f"на days_ahead дней вперёд, часовой пояс {TIMEZONE}. query — необязательный поиск по тексту.",
             {"days_ahead": int, "query": str, "days_back": int})(_wrap(calendar_events)),
        tool("calendar_create", f"Создать событие в Google Календаре. start/end — ISO 8601 "
             f"(2026-10-29T15:00:00) в часовом поясе {TIMEZONE}, или дата 2026-10-29 для события на весь день "
             f"(end — следующий день).",
             {"title": str, "start": str, "end": str, "description": str, "location": str})(_wrap(calendar_create)),
        tool("calendar_delete", "Удалить событие из Google Календаря по id.", {"event_id": str})(_wrap(calendar_delete)),
        tool("drive_search", "Поиск файлов на Google Диске по тексту.", {"query": str, "max_results": int})(_wrap(drive_search)),
        tool("drive_read", "Прочитать текст файла с Google Диска (Документы, Таблицы, Презентации, текстовые файлы).",
             {"file_id": str})(_wrap(drive_read)),
    ]
    return create_sdk_mcp_server(name="google", tools=tools)

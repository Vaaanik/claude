"""Второй мозг в Telegram: Claude Code на вашем компьютере, управляемый из Telegram.

Каждая тема (ветка) форум-группы — отдельный проект: своя папка и своя сессия Claude.
Текст и голосовые сообщения уходят в Claude, ответы приходят обратно в ту же тему.
"""

import asyncio
import json
import logging
import os
import re
import shutil
import tempfile
import threading
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from aiogram import Bot, Dispatcher, F
from aiogram.filters import Command, CommandObject
from aiogram.types import CallbackQuery, FSInputFile, InlineKeyboardButton, InlineKeyboardMarkup, Message
from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    TextBlock,
    ToolUseBlock,
    create_sdk_mcp_server,
    tool,
)
from dotenv import load_dotenv

load_dotenv()

import google_tools  # noqa: E402 — читает переменные из .env
from import_claude_export import import_export  # noqa: E402
from scheduler import Scheduler  # noqa: E402

BOT_TOKEN = os.environ["TELEGRAM_BOT_TOKEN"]
_ALLOWED = [int(x) for x in os.environ["ALLOWED_USER_IDS"].split(",") if x.strip()]
ALLOWED_USER_IDS = set(_ALLOWED)
# владелец: ему в личку приходят бэкапы и системные сообщения
OWNER_ID = _ALLOWED[0]
DEFAULT_CWD = os.path.expanduser(os.environ.get("DEFAULT_CWD", "~"))
# ask — спрашивать разрешение на изменения и команды; auto — разрешать всё без вопросов
PERMISSIONS = os.environ.get("PERMISSIONS", "ask")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL") or None
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")
WHISPER_LANGUAGE = os.environ.get("WHISPER_LANGUAGE", "ru")
STATE_FILE = Path(__file__).with_name("state.json")
# долговременная память: общий файл о пользователе, виден из всех тем
MEMORY_DIR = Path(os.path.expanduser(os.environ.get("MEMORY_DIR", "~/second-brain/memory")))
MEMORY_FILE = MEMORY_DIR / "about.md"
# архив чатов и проектов из экспорта claude.ai
CLAUDE_ARCHIVE = MEMORY_DIR.parent / "claude-archive"
# журнал всех входящих и исходящих сообщений, по файлу на день
HISTORY_DIR = MEMORY_DIR.parent / "history"
# корень второго мозга: память, заметки, задачи, журнал, архив
BRAIN_DIR = MEMORY_DIR.parent
NOTES_DIR = BRAIN_DIR / "notes"
TASKS_FILE = BRAIN_DIR / "tasks.md"
SCHEDULES_FILE = BRAIN_DIR / "schedules.json"
# сюда кладутся папки тем, созданных ботом
PROJECTS_DIR = Path(os.path.expanduser(os.environ.get("PROJECTS_DIR", "~/projects")))

# Инструменты, которые только читают — их разрешаем без вопросов
SAFE_TOOLS = {"Read", "Glob", "Grep", "LS", "WebSearch", "WebFetch", "TodoWrite", "NotebookRead"}

SYSTEM_PROMPT_APPEND = (
    "Ты — «второй мозг» пользователя, с тобой общаются через Telegram. Ты универсальный ассистент: "
    "отвечаешь на любые вопросы, а не только про код. Если нужны свежие данные (погода, новости, курсы, "
    "цены, расписания) — найди их через WebSearch, а затем обязательно открой подходящую страницу через "
    "WebFetch и дай конкретный ответ с цифрами. Не отправляй пользователя искать самому. "
    "Для погоды удобно открыть https://wttr.in/<город>?format=j1&lang=ru через WebFetch. "
    "Источник указывай одной ссылкой в конце, без раздела Sources со списком. "
    "Отвечай кратко и по делу, на языке пользователя. Telegram не рендерит Markdown-таблицы и заголовки, "
    "поэтому пиши простым текстом, списки — через дефис. Голосовые сообщения приходят расшифрованными "
    "и могут содержать ошибки распознавания — понимай по смыслу. "
    "На голосовые твой ответ озвучивается. Если в текстовом сообщении пользователь просит ответить голосом, "
    "начни ответ с метки [VOICE] — тогда он тоже будет озвучен. Озвучиваемый ответ пиши как живую речь: "
    "коротко, без списков, разметки, эмодзи, ссылок и раздела источников."
)
MEMORY_PROMPT = """
Долговременная память хранится на сервере и переживает перезапуски и новые разговоры.
- {memory_file} — общая память о пользователе для всех тем: кто он, где живёт, чем занимается, люди вокруг, цели, предпочтения, договорённости. Когда узнаёшь что-то устойчивое или пользователь просит «запомни» — сразу допиши это туда кратко, без дублей; устаревшее исправляй. Если спрашивают, что ты помнишь, — прочитай файл заново.
- CLAUDE.md в папке текущей темы — память этого проекта: цели, решения, статус, следующие шаги. Обновляй его, когда в проекте что-то решили или поменялось. Он подгружается автоматически в начале разговора.
Изменения этих файлов разрешены без вопросов. Не сообщай о каждом сохранении — максимум коротко «запомнил».

- {history} — полный журнал всех сообщений с пользователем во всех темах, файл на каждый день (ГГГГ-ММ-ДД.md). Если нужно вспомнить, о чём говорили раньше или в другой теме, — ищи там через Grep.
- {archive} — архив прошлых чатов и проектов пользователя из claude.ai (index.md — оглавление, chats/ — чаты, projects/ — проекты). Если вопрос может касаться прошлых разговоров («мы обсуждали…», «что я решил про…») — ищи там через Grep.

Структура второго мозга — папка {brain}. Файлы в ней меняй только инструментами Write и Edit (не через Bash) — так это происходит без вопросов к пользователю:
- {tasks} — задачи. Формат строки: «- [ ] задача (срок: ГГГГ-ММ-ДД) #проект», выполненные — «- [x]». Когда пользователь говорит «надо», «не забыть», «сделать» — добавь задачу; если есть срок и время — предложи напоминание. Когда сделал — отмечай.
- {notes}/ — заметки и знания: inbox.md (быстрые мысли без темы), ideas.md (идеи), links.md (ссылки: дата, ссылка, 1–2 строки о чём), people.md (люди: кто, контакты, о чём договорились), остальное — отдельными файлами по темам. Если пользователь прислал ссылку без вопроса — прочитай, коротко перескажи и сохрани в links.md. Если прислал мысль/идею без вопроса — сохрани и коротко подтверди. Если переслал чужое сообщение — пойми, что с ним сделать (задача, контакт, заметка, встреча), сделай и коротко скажи.
- Фото чеков, документов, визиток — разбери (Read умеет читать изображения) и сохрани важное в нужное место.

Расписание: инструменты schedule_add / schedule_list / schedule_cancel. mode=remind — в нужное время просто прислать текст напоминания (prompt — сам текст напоминания); mode=task — в нужное время выполнить prompt как задание (например, собрать сводку, проверить почту, сделать дайджест каналов) и прислать результат. Разовое — параметр at (локальное время ГГГГ-ММ-ДДTЧЧ:ММ), повторяющееся — cron (например «0 8 * * *» каждый день в 8:00, «0 9 * * 1» по понедельникам). Время — по часовому поясу пользователя. В начале каждого сообщения пользователя стоит текущие дата и время.

Google: инструменты gmail_*, calendar_*, drive_* дают доступ к почте, календарю и Диску пользователя. Пользуйся ими сам, когда вопрос их касается («что у меня завтра», «есть важные письма?», «найди договор»). Письма отправляй и события создавай/удаляй только по явной просьбе — пользователь подтвердит кнопкой. Если Google не подключён, скажи отправить /google.

Работа с темами Telegram: инструмент create_topic создаёт новую тему (ветку) в группе со своей папкой проекта, list_topics показывает существующие. Когда просят создать темы или ветки под проекты — используй их.

Текущее содержимое общей памяти:
{memory}
"""
TOOL_LABELS = {
    "WebSearch": "🔎 Ищу", "WebFetch": "🌐 Открываю", "Bash": "💻 Команда",
    "Read": "📖 Читаю", "mcp__telegram__create_topic": "🧵 Создаю тему",
    "mcp__telegram__schedule_add": "⏰ Планирую", "mcp__telegram__schedule_cancel": "🗑 Отменяю",
    "mcp__google__gmail_search": "📬 Смотрю почту", "mcp__google__gmail_read": "📧 Читаю письмо",
    "mcp__google__gmail_draft": "📝 Черновик письма", "mcp__google__gmail_send": "📤 Отправляю письмо",
    "mcp__google__calendar_events": "📅 Смотрю календарь", "mcp__google__calendar_create": "📅 Создаю событие",
    "mcp__google__calendar_delete": "🗑 Удаляю событие",
    "mcp__google__drive_search": "🗂 Ищу на Диске", "mcp__google__drive_read": "📄 Читаю файл", "Write": "📝 Пишу", "Edit": "✏️ Правлю", "Glob": "📂 Ищу файлы", "Grep": "🔍 Ищу в файлах",
}
# служебные шаги, которые пользователю показывать незачем
HIDDEN_TOOLS = {"ToolSearch", "TodoWrite", "mcp__telegram__list_topics", "mcp__telegram__schedule_list"}
VOICE_MARKER = "[VOICE]"
VOICE_INPUT_NOTE = "(голосовое сообщение — ответ будет озвучен)"
TTS_VOICE = os.environ.get("TTS_VOICE", "ru-RU-DmitryNeural")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("second_brain")

bot = Bot(BOT_TOKEN)
dp = Dispatcher()


# ---------- состояние: тема -> папка проекта и id сессии ----------

def load_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    return {}


def save_state() -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


state = load_state()
scheduler = Scheduler(SCHEDULES_FILE, google_tools.TIMEZONE)


def topic_key(message: Message) -> str:
    return f"{message.chat.id}:{message.message_thread_id or 0}"


def topic_state(key: str) -> dict:
    return state.setdefault(key, {"cwd": DEFAULT_CWD, "session_id": None})


def slugify(name: str) -> str:
    return re.sub(r"[^\w-]+", "-", name.lower()).strip("-") or "project"


# ---------- журнал сообщений ----------

def record(key: str, who: str, text: str) -> None:
    """Дописывает сообщение в журнал дня на сервере. Сбой записи не должен ломать ответ."""
    try:
        now = datetime.now(ZoneInfo(google_tools.TIMEZONE))
        topic = state.get(key, {}).get("name") or ("личка" if not key.startswith("-") else f"тема {key.split(':')[1]}")
        HISTORY_DIR.mkdir(parents=True, exist_ok=True)
        with open(HISTORY_DIR / f"{now:%Y-%m-%d}.md", "a", encoding="utf-8") as f:
            f.write(f"### {now:%H:%M:%S} · {topic} · {who}\n{text.strip()}\n\n")
    except Exception:
        log.exception("history write failed")


# ---------- память ----------

def memory_prompt() -> str:
    MEMORY_DIR.mkdir(parents=True, exist_ok=True)
    if not MEMORY_FILE.exists():
        MEMORY_FILE.write_text("# Что я знаю о пользователе\n\n", encoding="utf-8")
    memory = MEMORY_FILE.read_text(encoding="utf-8")
    if len(memory) > 20000:
        memory = memory[:20000] + "\n…(файл длиннее — дочитай при необходимости)"
    return MEMORY_PROMPT.format(memory_file=MEMORY_FILE, memory=memory, archive=CLAUDE_ARCHIVE, history=HISTORY_DIR,
                                brain=BRAIN_DIR, tasks=TASKS_FILE, notes=NOTES_DIR)


def is_memory_write(tool_name: str, tool_input: dict, cwd: str) -> bool:
    if tool_name not in ("Write", "Edit", "MultiEdit") or "file_path" not in tool_input:
        return False
    path = Path(tool_input["file_path"]).expanduser().resolve()
    return path.is_relative_to(BRAIN_DIR.resolve()) or path == Path(cwd).resolve() / "CLAUDE.md"


# ---------- инструменты Telegram для Claude ----------

def telegram_tools(chat_id: int, thread_id: int | None):
    @tool("create_topic", "Создать новую тему (ветку) в группе Telegram с отдельной папкой проекта.",
          {"name": str})
    async def create_topic(args):
        name = args["name"].strip()[:128]
        try:
            topic = await bot.create_forum_topic(chat_id, name)
        except Exception as e:
            return {"content": [{"type": "text", "text": (
                f"Не удалось создать тему: {e}. Темы можно создавать только в группе с включёнными "
                "Темами, где бот — администратор с правом управлять темами.")}], "is_error": True}
        folder = PROJECTS_DIR / slugify(name)
        folder.mkdir(parents=True, exist_ok=True)
        state[f"{chat_id}:{topic.message_thread_id}"] = {"cwd": str(folder), "session_id": None, "name": name}
        save_state()
        await bot.send_message(chat_id, f"📁 Тема «{name}» готова. Папка проекта: {folder}",
                               message_thread_id=topic.message_thread_id)
        return {"content": [{"type": "text", "text": f"Создана тема «{name}», папка {folder}"}]}

    @tool("list_topics", "Показать темы этой группы, которые знает бот, и их папки.", {})
    async def list_topics(args):
        rows = [f"- {v.get('name') or 'тема ' + k.split(':')[1]}: {v['cwd']}"
                for k, v in state.items() if k.startswith(f"{chat_id}:")]
        return {"content": [{"type": "text", "text": "\n".join(rows) or "Тем пока нет."}]}

    @tool("schedule_add", "Запланировать напоминание (mode=remind) или задание (mode=task) в этой теме. "
          "at — разовое время ГГГГ-ММ-ДДTЧЧ:ММ (иначе пустая строка), cron — повторение (иначе пустая строка). "
          "voice=true — прислать голосом.",
          {"title": str, "prompt": str, "mode": str, "at": str, "cron": str, "voice": bool})
    async def schedule_add(args):
        try:
            job = scheduler.add(title=args["title"], prompt=args["prompt"], mode=args["mode"],
                                chat_id=chat_id, thread_id=thread_id, at=args.get("at", ""),
                                cron=args.get("cron", ""), voice=bool(args.get("voice")))
        except Exception as e:
            return {"content": [{"type": "text", "text": f"Не получилось: {e}"}], "is_error": True}
        nxt = datetime.fromisoformat(job["next"]).strftime("%d.%m.%Y %H:%M")
        return {"content": [{"type": "text", "text": f"Запланировано [{job['id']}], ближайший раз {nxt}"}]}

    @tool("schedule_list", "Показать запланированные напоминания и задания.", {})
    async def schedule_list(args):
        return {"content": [{"type": "text", "text": scheduler.describe()}]}

    @tool("schedule_cancel", "Отменить запланированное по id.", {"id": str})
    async def schedule_cancel(args):
        ok = scheduler.cancel(args["id"])
        return {"content": [{"type": "text", "text": "Отменено." if ok else "Не нашёл такое задание."}]}

    return create_sdk_mcp_server(name="telegram", tools=[
        create_topic, list_topics, schedule_add, schedule_list, schedule_cancel])


# ---------- сессии Claude, по одной на тему ----------

class TopicSession:
    def __init__(self, key: str, chat_id: int, thread_id: int | None):
        self.key = key
        self.chat_id = chat_id
        self.thread_id = thread_id
        self.client: ClaudeSDKClient | None = None
        self.lock = asyncio.Lock()

    async def ensure_client(self) -> ClaudeSDKClient:
        if self.client is None:
            ts = topic_state(self.key)
            Path(ts["cwd"]).mkdir(parents=True, exist_ok=True)
            options = ClaudeAgentOptions(
                cwd=ts["cwd"],
                resume=ts["session_id"],
                model=CLAUDE_MODEL,
                system_prompt={"type": "preset", "preset": "claude_code",
                               "append": SYSTEM_PROMPT_APPEND + memory_prompt()},
                add_dirs=[BRAIN_DIR],
                mcp_servers={
                    "telegram": telegram_tools(self.chat_id, self.thread_id),
                    **({"google": google_tools.google_server()} if google_tools.configured() else {}),
                },
                setting_sources=["user", "project", "local"],
                permission_mode="bypassPermissions" if permissions_mode() == "auto" else "default",
                can_use_tool=None if permissions_mode() == "auto" else self.ask_permission,
            )
            self.client = ClaudeSDKClient(options)
            await self.client.connect()
        return self.client

    async def reset(self) -> None:
        if self.client is not None:
            try:
                await self.client.disconnect()
            except Exception:
                log.exception("disconnect failed")
            self.client = None

    async def ask_permission(self, tool_name, tool_input, context):
        if (tool_name in SAFE_TOOLS or tool_name in google_tools.SAFE_GOOGLE_TOOLS
                or tool_name.startswith("mcp__telegram__")
                or is_memory_write(tool_name, tool_input, topic_state(self.key)["cwd"])):
            return PermissionResultAllow()
        request_id = uuid.uuid4().hex[:12]
        future = asyncio.get_running_loop().create_future()
        pending_permissions[request_id] = future
        keyboard = InlineKeyboardMarkup(inline_keyboard=[[
            InlineKeyboardButton(text="✅ Разрешить", callback_data=f"perm:{request_id}:1"),
            InlineKeyboardButton(text="❌ Отклонить", callback_data=f"perm:{request_id}:0"),
        ]])
        await bot.send_message(
            self.chat_id,
            f"🔐 Подтвердите — {TOOL_LABELS.get(tool_name, tool_name)}:\n\n"
            f"{describe_tool(tool_name, tool_input, 1500)}",
            message_thread_id=self.thread_id,
            reply_markup=keyboard,
        )
        try:
            allowed = await asyncio.wait_for(future, timeout=600)
        except asyncio.TimeoutError:
            allowed = False
        finally:
            pending_permissions.pop(request_id, None)
        if allowed:
            return PermissionResultAllow()
        return PermissionResultDeny(message="Пользователь отклонил это действие.")


sessions: dict[str, TopicSession] = {}
pending_permissions: dict[str, asyncio.Future] = {}


def get_session(message: Message) -> TopicSession:
    return session_for(message.chat.id, message.message_thread_id)


def session_for(chat_id: int, thread_id: int | None) -> TopicSession:
    key = f"{chat_id}:{thread_id or 0}"
    if key not in sessions:
        sessions[key] = TopicSession(key, chat_id, thread_id)
    return sessions[key]


def permissions_mode() -> str:
    return state.get("_settings", {}).get("permissions", PERMISSIONS)


WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]


def now_stamp() -> str:
    now = scheduler.now()
    return f"[Сейчас {now:%Y-%m-%d %H:%M}, {WEEKDAYS[now.weekday()]}, {google_tools.TIMEZONE}]"


def describe_tool(name: str, tool_input: dict, limit: int = 200) -> str:
    if name == "Bash":
        text = tool_input.get("command", "")
    elif name == "WebSearch":
        text = tool_input.get("query", "")
    elif name == "WebFetch":
        text = tool_input.get("url", "")
    elif name == "mcp__telegram__create_topic":
        text = tool_input.get("name", "")
    elif name == "mcp__telegram__schedule_add":
        text = f"{tool_input.get('title')} ({tool_input.get('at') or tool_input.get('cron')})"
    elif name.startswith("mcp__google__") and limit < 1000:
        text = tool_input.get("query") or tool_input.get("title") or tool_input.get("to") or ""
    elif name in ("mcp__google__gmail_send", "mcp__google__gmail_draft"):
        text = f"Кому: {tool_input.get('to')}\nТема: {tool_input.get('subject')}\n\n{tool_input.get('body')}"
    elif name == "mcp__google__calendar_create":
        text = (f"{tool_input.get('title')}\n{tool_input.get('start')} → {tool_input.get('end')}"
                f"\n{tool_input.get('location') or ''}")
    elif "file_path" in tool_input:
        text = tool_input["file_path"]
    else:
        text = json.dumps(tool_input, ensure_ascii=False)
    return text if len(text) <= limit else text[:limit] + "…"


async def send_long(chat_id: int, thread_id: int | None, text: str) -> None:
    for i in range(0, len(text), 4000):
        await bot.send_message(chat_id, text[i:i + 4000], message_thread_id=thread_id)


async def keep_typing(chat_id: int, thread_id: int | None, action: str) -> None:
    while True:
        try:
            await bot.send_chat_action(chat_id, action, message_thread_id=thread_id)
        except Exception:
            pass
        await asyncio.sleep(4)


async def ask_claude(message: Message, prompt: str, voice: bool = False) -> None:
    session = get_session(message)
    if session.lock.locked():
        await message.reply("⏳ Ещё работаю над прошлым сообщением, это поставил в очередь.")
    await run_claude(session, prompt, voice)


async def run_claude(session: TopicSession, prompt: str, voice: bool = False, who: str | None = None) -> None:
    async def reply(text: str) -> None:
        await bot.send_message(session.chat_id, text, message_thread_id=session.thread_id)

    async with session.lock:
        typing = asyncio.create_task(
            keep_typing(session.chat_id, session.thread_id, "record_voice" if voice else "typing")
        )
        status: Message | None = None
        steps: list[str] = []
        spoken: list[str] = []
        record(session.key, who or ("🙋 Я (голос)" if voice else "🙋 Я"), prompt.removeprefix(VOICE_INPUT_NOTE))
        try:
            client = await session.ensure_client()
            await client.query(f"{now_stamp()}\n{prompt}")
            async for msg in client.receive_response():
                if isinstance(msg, AssistantMessage):
                    for block in msg.content:
                        if isinstance(block, TextBlock) and block.text.strip():
                            text = block.text
                            # Claude ставит метку, если в текстовом сообщении попросили ответить голосом
                            if text.lstrip().startswith(VOICE_MARKER):
                                voice = True
                                text = text.lstrip()[len(VOICE_MARKER):].strip()
                            if voice:
                                spoken.append(text)
                            else:
                                record(session.key, "🧠 Бот", text)
                                await send_long(session.chat_id, session.thread_id, text)
                        elif isinstance(block, ToolUseBlock) and block.name not in HIDDEN_TOOLS:
                            # один статус-сообщение со списком шагов, чтобы не спамить
                            label = TOOL_LABELS.get(block.name, f"🔧 {block.name}")
                            steps.append(f"{label}: {describe_tool(block.name, block.input, 80)}")
                            record(session.key, "⚙️ Действие", steps[-1])
                            text = "\n".join(steps[-8:])
                            if status is None:
                                status = await bot.send_message(
                                    session.chat_id, text, message_thread_id=session.thread_id
                                )
                            else:
                                try:
                                    await status.edit_text(text)
                                except Exception:
                                    pass
                elif isinstance(msg, ResultMessage):
                    ts = topic_state(session.key)
                    ts["session_id"] = msg.session_id
                    save_state()
                    if msg.is_error:
                        await reply(f"⚠️ Ошибка: {msg.result or msg.subtype}")
            if spoken:
                record(session.key, "🧠 Бот (голос)", "\n\n".join(spoken))
                await send_voice_reply(session.chat_id, session.thread_id, "\n\n".join(spoken))
        except Exception as e:
            log.exception("claude call failed")
            await session.reset()
            record(session.key, "⚠️ Ошибка", str(e))
            await reply(f"⚠️ Не получилось: {e}")
        finally:
            typing.cancel()


# ---------- голос ----------

_whisper = None
_whisper_lock = threading.Lock()


def transcribe_model():
    global _whisper
    with _whisper_lock:
        if _whisper is None:
            from faster_whisper import WhisperModel
            _whisper = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
    return _whisper


def transcribe(path: str) -> str:
    segments, _ = transcribe_model().transcribe(path, language=WHISPER_LANGUAGE or None, vad_filter=True)
    return " ".join(s.text.strip() for s in segments).strip()


def clean_for_speech(text: str) -> str:
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"https?://\S+", " ", text)
    text = re.sub(r"[*_#`>|]", "", text)
    return re.sub(r"[ \t]+", " ", text).strip()


def mp3_to_ogg(src: str, dest: str) -> None:
    # Telegram показывает как голосовое только OGG/Opus
    import av
    with av.open(src) as inp, av.open(dest, "w", format="ogg") as out:
        stream = out.add_stream("libopus", rate=48000)
        stream.layout = "mono"
        resampler = av.AudioResampler(format="s16", layout="mono", rate=48000)
        for frame in inp.decode(audio=0):
            for f in resampler.resample(frame):
                for packet in stream.encode(f):
                    out.mux(packet)
        for f in resampler.resample(None):
            for packet in stream.encode(f):
                out.mux(packet)
        for packet in stream.encode(None):
            out.mux(packet)


async def synthesize(text: str, dest: str) -> None:
    mp3 = dest + ".mp3"
    try:
        import edge_tts
        await edge_tts.Communicate(text, TTS_VOICE).save(mp3)
    except Exception:
        log.warning("edge-tts недоступен, озвучиваю через gTTS", exc_info=True)
        from gtts import gTTS
        await asyncio.to_thread(lambda: gTTS(text, lang=WHISPER_LANGUAGE or "ru").save(mp3))
    await asyncio.to_thread(mp3_to_ogg, mp3, dest)


async def send_voice_reply(chat_id: int, thread_id: int | None, text: str) -> None:
    speech = clean_for_speech(text)
    try:
        if not speech:
            raise ValueError("нечего озвучивать")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "reply.ogg")
            await synthesize(speech, path)
            await bot.send_voice(chat_id, FSInputFile(path), message_thread_id=thread_id)
    except Exception:
        log.exception("tts failed")
        await send_long(chat_id, thread_id, text)
        return
    # код и ссылки голосом не передать — присылаем только их текстом, без повтора всего ответа
    extras = re.findall(r"```.*?```", text, flags=re.S)
    extras += list(dict.fromkeys(re.findall(r"https?://[^\s)\]]+", text)))
    if extras:
        await send_long(chat_id, thread_id, "\n\n".join(extras))


# ---------- доступ ----------

@dp.message.outer_middleware()
async def only_owner(handler, event: Message, data):
    if event.from_user is None or event.from_user.id not in ALLOWED_USER_IDS:
        log.warning("ignored message from %s", event.from_user and event.from_user.id)
        return None
    return await handler(event, data)


# ---------- команды ----------

@dp.message(Command("start", "help"))
async def cmd_help(message: Message):
    ts = topic_state(topic_key(message))
    await message.reply(
        "🧠 Второй мозг на связи. Пишите текстом или голосом.\n\n"
        "Что можно просить:\n"
        "- любые вопросы, поиск в интернете, погода, новости\n"
        "- «напомни завтра в 10 позвонить маме», «каждое утро в 8 присылай сводку»\n"
        "- «надо купить подарок до пятницы» — запишу в задачи\n"
        "- идеи, мысли, ссылки, пересланные сообщения, фото чеков — разберу и сохраню\n"
        "- почта, календарь, Google Диск (после /google)\n"
        "- «создай темы для проектов: …»\n"
        "- «что мы решили про…» — найду в памяти и журнале\n\n"
        "Команды:\n"
        "/brief — сводка на сегодня\n"
        "/tasks — мои задачи\n"
        "/schedule — что запланировано\n"
        "/new — начать разговор в теме заново\n"
        "/stop — прервать текущую задачу\n"
        "/mode — спрашивать ли подтверждение действий\n"
        "/backup — прислать архив памяти\n"
        "/google — подключить почту и календарь\n"
        "/project <путь> — привязать тему к папке\n\n"
        f"Папка этой темы: {ts['cwd']}"
    )


BRIEF_PROMPT = (
    "Сделай мою сводку на сегодня, коротко и по делу: события календаря на сегодня и завтра утром; "
    "важные непрочитанные письма за последние сутки (рассылки и спам пропусти); погода на сегодня в моём "
    "городе; задачи из списка задач — просроченные и на сегодня. Если Google не подключён, пропусти почту "
    "и календарь без лишних слов. В конце — одна мысль, на чём сфокусироваться."
)


@dp.message(Command("brief"))
async def cmd_brief(message: Message):
    await ask_claude(message, BRIEF_PROMPT)


@dp.message(Command("tasks"))
async def cmd_tasks(message: Message):
    await ask_claude(message, "Покажи мои открытые задачи, сгруппируй по срокам: просроченные, сегодня, "
                              "на неделе, без срока.")


@dp.message(Command("schedule"))
async def cmd_schedule(message: Message):
    await message.reply("🗓 Запланировано:\n" + scheduler.describe() +
                        "\n\nОтменить: «отмени напоминание …» или «отмени задание [id]».")


@dp.message(Command("mode"))
async def cmd_mode(message: Message, command: CommandObject):
    arg = (command.args or "").strip().lower()
    if arg not in ("auto", "ask"):
        await message.reply(
            f"Сейчас: {permissions_mode()}.\n\n"
            "/mode ask — спрашивать подтверждение перед командами, изменением файлов, отправкой писем\n"
            "/mode auto — делать всё без вопросов (быстрее, но бот сможет отправить письмо или удалить файл сам)"
        )
        return
    state.setdefault("_settings", {})["permissions"] = arg
    save_state()
    for sess in sessions.values():
        if not sess.lock.locked():
            await sess.reset()
    await message.reply("✅ Режим: " + ("без вопросов" if arg == "auto" else "с подтверждением"))


@dp.message(Command("backup"))
async def cmd_backup(message: Message):
    await send_backup(message.chat.id, message.message_thread_id)


@dp.message(Command("google"))
async def cmd_google(message: Message):
    if not google_tools.configured():
        await message.reply("Сначала впишите GOOGLE_CLIENT_ID и GOOGLE_CLIENT_SECRET в .env на сервере "
                            "и перезапустите бота.")
        return
    await message.reply(
        "🔑 Подключение Google:\n\n"
        "1. Откройте ссылку ниже и войдите в свой Google-аккаунт.\n"
        "2. Если Google предупредит, что приложение не проверено, — «Дополнительно» → «Перейти».\n"
        "3. Разрешите доступ. Браузер откроет страницу localhost, которая не загрузится, — так и должно быть.\n"
        "4. Скопируйте адрес этой страницы целиком (начинается с http://localhost) и пришлите мне.\n\n"
        + google_tools.auth_url()
    )


@dp.message(Command("id"))
async def cmd_id(message: Message):
    await message.reply(f"Ваш ID: {message.from_user.id}\nЧат: {message.chat.id}")


@dp.message(Command("project"))
async def cmd_project(message: Message, command: CommandObject):
    if not command.args:
        await message.reply("Укажите путь: /project ~/projects/my-app")
        return
    path = Path(os.path.expanduser(command.args.strip())).resolve()
    path.mkdir(parents=True, exist_ok=True)
    session = get_session(message)
    await session.reset()
    ts = topic_state(session.key)
    ts["cwd"] = str(path)
    ts["session_id"] = None
    save_state()
    await message.reply(f"📁 Тема привязана к {path}. Начинаем с чистого листа.")


@dp.message(Command("new"))
async def cmd_new(message: Message):
    session = get_session(message)
    await session.reset()
    topic_state(session.key)["session_id"] = None
    save_state()
    await message.reply("🆕 Новый разговор. Папка проекта та же.")


@dp.message(Command("stop"))
async def cmd_stop(message: Message):
    session = get_session(message)
    if session.client is not None and session.lock.locked():
        await session.client.interrupt()
        await message.reply("⏹ Остановил.")
    else:
        await message.reply("Сейчас ничего не выполняется.")


@dp.message(Command("where"))
async def cmd_where(message: Message):
    ts = topic_state(topic_key(message))
    await message.reply(f"📁 {ts['cwd']}\n🧵 сессия: {ts['session_id'] or 'новая'}")


# ---------- сообщения ----------

@dp.message(F.voice | F.audio | F.video_note)
async def on_voice(message: Message):
    media = message.voice or message.audio or message.video_note
    try:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "voice.ogg")
            await bot.download(media, destination=path)
            text = await asyncio.to_thread(transcribe, path)
    except Exception as e:
        log.exception("transcription failed")
        await message.reply(f"⚠️ Не получилось распознать голосовое: {e}")
        return
    if not text:
        await message.reply("🤷 Не разобрал голосовое.")
        return
    log.info("voice: %s", text)
    await ask_claude(message, f"{VOICE_INPUT_NOTE}\n\n{text}", voice=True)


async def import_if_claude_export(message: Message, path: Path) -> bool:
    try:
        summary = await asyncio.to_thread(import_export, path, CLAUDE_ARCHIVE)
    except ValueError:
        return False  # обычный zip, не экспорт claude.ai
    await message.reply(f"📦 Архив claude.ai загружен на сервер.\n{summary}\n\nТеперь соберу из него память…")
    await ask_claude(message, (
        f"Я загрузил экспорт своих данных из claude.ai в {CLAUDE_ARCHIVE}. Прочитай index.md, файлы памяти "
        f"Claude, если они есть в архиве (json-файлы в корне), и README.md проектов. Перенеси в {MEMORY_FILE} "
        "устойчивые факты обо мне: кто я, чем занимаюсь, проекты, люди, предпочтения. Чаты целиком не читай — "
        "только то, что нужно. В конце коротко скажи, что запомнил и какие проекты нашёл."
    ))
    return True


@dp.message(F.document | F.photo)
async def on_file(message: Message):
    ts = topic_state(topic_key(message))
    inbox = Path(ts["cwd"]) / "telegram_inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    if message.document:
        dest = inbox / message.document.file_name
        try:
            await bot.download(message.document, destination=dest)
        except Exception as e:
            await message.reply(f"⚠️ Не смог скачать файл: {e}\nTelegram отдаёт ботам файлы до 20 МБ. "
                                "Большой файл загрузите на Google Диск, откройте доступ по ссылке и пришлите ссылку.")
            return
        if dest.suffix.lower() == ".zip" and await import_if_claude_export(message, dest):
            return
    else:
        dest = inbox / f"photo_{message.message_id}.jpg"
        await bot.download(message.photo[-1], destination=dest)
    caption = message.caption or "Посмотри этот файл."
    await ask_claude(message, f"Я прислал файл: {dest}\n\n{caption}")


@dp.message(F.text)
async def on_text(message: Message):
    if google_tools.is_auth_redirect(message.text):
        try:
            await asyncio.to_thread(google_tools.finish_auth, message.text)
        except Exception as e:
            await message.reply(f"⚠️ Не получилось подключить Google: {e}")
            return
        # пересоздаём сессии, чтобы инструменты Google подхватили доступ
        for s in sessions.values():
            if not s.lock.locked():
                await s.reset()
        await message.reply("✅ Google подключён: почта, календарь и Диск доступны.")
        return
    text = message.text
    origin = forward_label(message)
    if origin:
        text = f"[Пересланное сообщение от {origin}]\n{text}"
    await ask_claude(message, text)


def forward_label(message: Message) -> str | None:
    o = message.forward_origin
    if o is None:
        return None
    for attr in ("sender_user", "sender_chat", "chat"):
        obj = getattr(o, attr, None)
        if obj is not None:
            name = getattr(obj, "full_name", None) or getattr(obj, "title", None) or "?"
            username = getattr(obj, "username", None)
            return f"{name} (@{username})" if username else name
    return getattr(o, "sender_user_name", None) or "неизвестного"


# ---------- расписание и бэкап ----------

async def scheduler_loop() -> None:
    while True:
        try:
            for job in scheduler.due():
                asyncio.create_task(run_job(job))
        except Exception:
            log.exception("scheduler tick failed")
        await asyncio.sleep(20)


async def run_job(job: dict) -> None:
    chat_id, thread_id = job["chat_id"], job["thread_id"]
    try:
        if job["mode"] == "backup":
            await send_backup(chat_id, thread_id)
        elif job["mode"] == "remind":
            record(f"{chat_id}:{thread_id or 0}", "⏰ Напоминание", job["prompt"])
            if job.get("voice"):
                await send_voice_reply(chat_id, thread_id, job["prompt"])
            else:
                await bot.send_message(chat_id, f"⏰ {job['prompt']}", message_thread_id=thread_id)
        else:
            prompt = f"[Запланированное задание «{job['title']}»]\n{job['prompt']}"
            if job.get("voice"):
                prompt = f"{VOICE_INPUT_NOTE}\n\n{prompt}"
            await run_claude(session_for(chat_id, thread_id), prompt, voice=bool(job.get("voice")),
                             who="🗓 Расписание")
    except Exception:
        log.exception("job %s failed", job["id"])


async def send_backup(chat_id: int, thread_id: int | None) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        stamp = scheduler.now().strftime("%Y-%m-%d")
        staging = Path(tmp) / "second-brain"
        await asyncio.to_thread(shutil.copytree, BRAIN_DIR, staging, dirs_exist_ok=True)
        if STATE_FILE.exists():
            shutil.copy(STATE_FILE, staging / "bot_state.json")
        archive = await asyncio.to_thread(shutil.make_archive, str(Path(tmp) / f"second-brain-{stamp}"),
                                          "zip", tmp, "second-brain")
        size_mb = Path(archive).stat().st_size / 1024 / 1024
        if size_mb > 49:
            await bot.send_message(chat_id, f"⚠️ Архив памяти {size_mb:.0f} МБ — больше лимита Telegram (50 МБ). "
                                            "Скачайте папку ~/second-brain с сервера вручную.",
                                   message_thread_id=thread_id)
            return
        await bot.send_document(chat_id, FSInputFile(archive), message_thread_id=thread_id,
                                caption=f"🗄 Бэкап второго мозга за {stamp} ({size_mb:.1f} МБ)")


def ensure_default_jobs() -> None:
    if not any(j["mode"] == "backup" for j in scheduler.jobs):
        scheduler.add(title="Еженедельный бэкап", prompt="", mode="backup", chat_id=OWNER_ID,
                      thread_id=None, cron="0 21 * * 0")


@dp.callback_query(F.data.startswith("perm:"))
async def on_permission(callback: CallbackQuery):
    if callback.from_user.id not in ALLOWED_USER_IDS:
        await callback.answer()
        return
    _, request_id, decision = callback.data.split(":")
    future = pending_permissions.get(request_id)
    if future is None or future.done():
        await callback.answer("Запрос уже неактуален")
        return
    allowed = decision == "1"
    future.set_result(allowed)
    record(f"{callback.message.chat.id}:{callback.message.message_thread_id or 0}",
           "✅ Разрешено" if allowed else "❌ Отклонено", callback.message.text)
    await callback.answer("Разрешено" if allowed else "Отклонено")
    await callback.message.edit_text(callback.message.text + ("\n\n✅ Разрешено" if allowed else "\n\n❌ Отклонено"))


async def main() -> None:
    log.info("Второй мозг запущен. Разрешённые пользователи: %s", ALLOWED_USER_IDS)
    # модель распознавания грузим заранее, чтобы первое голосовое не ждало скачивания
    asyncio.get_running_loop().run_in_executor(None, preload_whisper)
    BRAIN_DIR.mkdir(parents=True, exist_ok=True)
    NOTES_DIR.mkdir(parents=True, exist_ok=True)
    if not TASKS_FILE.exists():
        TASKS_FILE.write_text("# Задачи\n\n", encoding="utf-8")
    ensure_default_jobs()
    asyncio.create_task(scheduler_loop())
    await dp.start_polling(bot)


def preload_whisper() -> None:
    try:
        transcribe_model()
        log.info("Модель распознавания голоса готова")
    except Exception:
        log.exception("не удалось загрузить модель распознавания")


if __name__ == "__main__":
    asyncio.run(main())

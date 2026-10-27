"""Второй мозг в Telegram: Claude Code на вашем компьютере, управляемый из Telegram.

Каждая тема (ветка) форум-группы — отдельный проект: своя папка и своя сессия Claude.
Текст и голосовые сообщения уходят в Claude, ответы приходят обратно в ту же тему.
"""

import asyncio
import json
import logging
import os
import re
import tempfile
import threading
import uuid
from pathlib import Path

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
)
from dotenv import load_dotenv

load_dotenv()

BOT_TOKEN = os.environ["TELEGRAM_BOT_TOKEN"]
ALLOWED_USER_IDS = {int(x) for x in os.environ["ALLOWED_USER_IDS"].split(",") if x.strip()}
DEFAULT_CWD = os.path.expanduser(os.environ.get("DEFAULT_CWD", "~"))
# ask — спрашивать разрешение на изменения и команды; auto — разрешать всё без вопросов
PERMISSIONS = os.environ.get("PERMISSIONS", "ask")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL") or None
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")
WHISPER_LANGUAGE = os.environ.get("WHISPER_LANGUAGE", "ru")
STATE_FILE = Path(__file__).with_name("state.json")

# Инструменты, которые только читают — их разрешаем без вопросов
SAFE_TOOLS = {"Read", "Glob", "Grep", "LS", "WebSearch", "WebFetch", "TodoWrite", "NotebookRead"}

SYSTEM_PROMPT_APPEND = (
    "Ты — «второй мозг» пользователя, с тобой общаются через Telegram. "
    "Отвечай кратко и по делу, на языке пользователя. Telegram не рендерит Markdown-таблицы и заголовки, "
    "поэтому пиши простым текстом, списки — через дефис. Голосовые сообщения приходят расшифрованными "
    "и могут содержать ошибки распознавания — понимай по смыслу. "
    "На голосовые твой ответ озвучивается. Если в текстовом сообщении пользователь просит ответить голосом, "
    "начни ответ с метки [VOICE] — тогда он тоже будет озвучен. Озвучиваемый ответ пиши как живую речь: "
    "коротко, без списков, разметки, эмодзи и ссылок."
)
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


def topic_key(message: Message) -> str:
    return f"{message.chat.id}:{message.message_thread_id or 0}"


def topic_state(key: str) -> dict:
    return state.setdefault(key, {"cwd": DEFAULT_CWD, "session_id": None})


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
                system_prompt={"type": "preset", "preset": "claude_code", "append": SYSTEM_PROMPT_APPEND},
                setting_sources=["user", "project", "local"],
                permission_mode="bypassPermissions" if PERMISSIONS == "auto" else "default",
                can_use_tool=None if PERMISSIONS == "auto" else self.ask_permission,
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
        if tool_name in SAFE_TOOLS:
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
            f"🔐 Claude хочет выполнить {tool_name}:\n\n{describe_tool(tool_name, tool_input, 1500)}",
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
    key = topic_key(message)
    if key not in sessions:
        sessions[key] = TopicSession(key, message.chat.id, message.message_thread_id)
    return sessions[key]


def describe_tool(name: str, tool_input: dict, limit: int = 200) -> str:
    if name == "Bash":
        text = tool_input.get("command", "")
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
    async with session.lock:
        typing = asyncio.create_task(
            keep_typing(session.chat_id, session.thread_id, "record_voice" if voice else "typing")
        )
        status: Message | None = None
        steps: list[str] = []
        spoken: list[str] = []
        try:
            client = await session.ensure_client()
            await client.query(prompt)
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
                                await send_long(session.chat_id, session.thread_id, text)
                        elif isinstance(block, ToolUseBlock):
                            # один статус-сообщение со списком шагов, чтобы не спамить
                            steps.append(f"🔧 {block.name}: {describe_tool(block.name, block.input, 80)}")
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
                        await message.reply(f"⚠️ Ошибка: {msg.result or msg.subtype}")
            if spoken:
                await send_voice_reply(session.chat_id, session.thread_id, "\n\n".join(spoken))
        except Exception as e:
            log.exception("claude call failed")
            await session.reset()
            await message.reply(f"⚠️ Не получилось: {e}")
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
    # код и ссылки голосом не передать — дублируем их текстом
    if "```" in text or "http" in text:
        await send_long(chat_id, thread_id, text)


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
        "🧠 Второй мозг на связи.\n\n"
        "Пишите текстом или голосом — я работаю на вашем компьютере.\n"
        "Каждая тема группы — отдельный проект со своей памятью.\n\n"
        "/project <путь> — привязать эту тему к папке проекта\n"
        "/new — начать разговор в теме заново\n"
        "/stop — прервать текущую задачу\n"
        "/where — показать папку и сессию темы\n"
        "/id — показать ваш Telegram ID\n\n"
        f"Папка этой темы: {ts['cwd']}"
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


@dp.message(F.document | F.photo)
async def on_file(message: Message):
    ts = topic_state(topic_key(message))
    inbox = Path(ts["cwd"]) / "telegram_inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    if message.document:
        dest = inbox / message.document.file_name
        await bot.download(message.document, destination=dest)
    else:
        dest = inbox / f"photo_{message.message_id}.jpg"
        await bot.download(message.photo[-1], destination=dest)
    caption = message.caption or "Посмотри этот файл."
    await ask_claude(message, f"Я прислал файл: {dest}\n\n{caption}")


@dp.message(F.text)
async def on_text(message: Message):
    await ask_claude(message, message.text)


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
    await callback.answer("Разрешено" if allowed else "Отклонено")
    await callback.message.edit_text(callback.message.text + ("\n\n✅ Разрешено" if allowed else "\n\n❌ Отклонено"))


async def main() -> None:
    log.info("Второй мозг запущен. Разрешённые пользователи: %s", ALLOWED_USER_IDS)
    # модель распознавания грузим заранее, чтобы первое голосовое не ждало скачивания
    asyncio.get_running_loop().run_in_executor(None, preload_whisper)
    await dp.start_polling(bot)


def preload_whisper() -> None:
    try:
        transcribe_model()
        log.info("Модель распознавания голоса готова")
    except Exception:
        log.exception("не удалось загрузить модель распознавания")


if __name__ == "__main__":
    asyncio.run(main())
